// Web SDK tests with a fake browser (no dependencies). Run: node --test sdk-web/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SOURCE = readFileSync(new URL("../holdmyio.js", import.meta.url), "utf8");

function memoryStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    _map: m,
  };
}

/** Fake browser; returns window and the sent bodies. */
const tick = () => new Promise((r) => setTimeout(r, 0));

function browser({ href = "https://liffer.com/pricing?utm_source=news&email=x@y.lt#top", referrer = "https://www.google.com/search?q=liffer", attrs = {}, webdriver = false, ua = "Mozilla/5.0 (Macintosh) Firefox/140", lang = "", clock = null } = {}) {
  const sent = [];
  const listeners = {};
  const docListeners = {};
  const url = new URL(href);
  const location = { href: url.href, pathname: url.pathname, search: url.search, host: url.host, hostname: url.hostname };
  const script = {
    getAttribute: (n) => (n in attrs ? attrs[n] : n === "src" ? "https://io.holdmy.company/sdk/holdmyio.js" : null),
    hasAttribute: (n) => n in attrs,
  };
  const history = {
    pushState(_s, _t, u) { const next = new URL(u, location.href); location.pathname = next.pathname; location.search = next.search; },
    replaceState(_s, _t, u) { const next = new URL(u, location.href); location.pathname = next.pathname; location.search = next.search; },
  };
  const window = {
    location, history, URL, URLSearchParams, JSON, Date: clock ? { now: () => clock.t } : Date, Math, Uint8Array, Array, isFinite, String,
    crypto: globalThis.crypto,
    navigator: { userAgent: ua, language: "lt-LT", webdriver },
    document: { currentScript: script, referrer, visibilityState: "visible", addEventListener: (ev, fn) => { (docListeners[ev] ||= []).push(fn); }, documentElement: { lang } },
    localStorage: memoryStorage(),
    sessionStorage: memoryStorage(),
    // Like the real one: the SDK stores the timer first and the function runs later (here, right after).
    setTimeout: (fn) => { queueMicrotask(fn); return 1; },
    addEventListener: (ev, fn) => { (listeners[ev] ||= []).push(fn); },
    fetch: (u, init) => { sent.push({ url: u, headers: init.headers, keepalive: init.keepalive, body: JSON.parse(init.body) }); return Promise.resolve({ ok: true }); },
  };
  window.window = window;
  vm.runInNewContext(SOURCE, window);
  const events = () => sent.flatMap((s) => s.body.events.map((e) => ({ ...e, context: s.body.context })));
  const visibility = (state) => { window.document.visibilityState = state; (docListeners.visibilitychange || []).forEach((f) => f()); };
  return { window, sent, events, emit: (ev) => (listeners[ev] || []).forEach((f) => f()), visibility };
}

const ATTRS = { "data-key": "pk_live_web_0123456789abcdef" };

test("without consent: first page is anonymous, nothing stored in the browser", async () => {
  const b = browser({ attrs: ATTRS });
  await tick();
  assert.equal(b.sent.length, 1);
  const { url, headers, keepalive, body } = b.sent[0];
  assert.equal(url, "https://io.holdmy.company/v1/events");
  assert.equal(headers["x-app-key"], "pk_live_web_0123456789abcdef");
  assert.equal(keepalive, true);
  assert.equal(body.context.anonymous, true);
  assert.equal(body.context.install_id, undefined);
  assert.equal(body.context.platform, "web");
  assert.equal(body.context.device_model, "desktop");
  const e = body.events[0];
  assert.equal(e.name, "screen_view");
  assert.equal(e.screen, "/pricing");
  assert.equal(e.session_id, "");
  assert.deepEqual(e.props, { referrer: "www.google.com", utm_source: "news" });
  assert.ok(!JSON.stringify(body).includes("x@y.lt"), "query parameters (except utm) are not sent");
  assert.ok(!JSON.stringify(body).includes("#top"));
  assert.equal(b.window.localStorage._map.size, 0, "localStorage is not used");
  assert.equal(b.window.sessionStorage._map.size, 0, "sessionStorage is not used");
});

test("SPA: a new path is a new view without referrer; the same path is not repeated", async () => {
  const b = browser({ attrs: ATTRS });
  b.window.history.pushState({}, "", "/library?tab=all");
  b.window.history.replaceState({}, "", "/library?tab=pro");
  b.emit("popstate");
  await tick();
  const views = b.events().filter((e) => e.name === "screen_view");
  assert.deepEqual(views.map((e) => e.screen), ["/pricing", "/library"]);
  assert.deepEqual(views[1].props, {});
});

test("consent: persistent ID and session; revoking deletes them and goes anonymous again", async () => {
  const b = browser({ attrs: ATTRS });
  await tick();
  b.window.HoldMyIO.consent(true);
  const id = b.window.localStorage.getItem("holdmyio_id");
  assert.match(id, /^[0-9A-F-]{36}$/);
  b.window.HoldMyIO.track("signup", { plan: "pro", seats: 3, nested: { no: 1 } });
  await tick();
  const signup = b.events().find((e) => e.name === "signup");
  assert.equal(signup.context.install_id, id);
  assert.equal(signup.context.anonymous, undefined);
  assert.match(signup.session_id, /^[0-9a-f-]{36}$/);
  assert.deepEqual(signup.props, { plan: "pro", seats: 3 });
  assert.equal(signup.screen, "/pricing");

  b.window.HoldMyIO.consent(false);
  assert.equal(b.window.localStorage.getItem("holdmyio_id"), null);
  assert.equal(b.window.sessionStorage.getItem("holdmyio_session"), null);
  b.window.HoldMyIO.track("after");
  await tick();
  const after = b.events().find((e) => e.name === "after");
  assert.equal(after.context.anonymous, true);
  assert.equal(after.session_id, "");
});

test("automated browser sends nothing; without data-key the SDK does nothing", async () => {
  const bot = browser({ attrs: ATTRS, webdriver: true });
  await tick();
  assert.equal(bot.sent.length, 0);
  const googlebot = browser({ attrs: ATTRS, ua: "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Chrome/131.0.0.0 Safari/537.36" });
  const headless = browser({ attrs: ATTRS, ua: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/131.0.0.0 Safari/537.36" });
  await tick();
  assert.equal(googlebot.sent.length + headless.sent.length, 0, "known bots send nothing");
  const none = browser({ attrs: {} });
  none.window.HoldMyIO.track("x");
  await tick();
  assert.equal(none.sent.length, 0);
});

test("settings: debug on localhost, data-endpoint, data-pageviews=false, mobile device", async () => {
  const local = browser({ href: "http://localhost:5173/", attrs: ATTRS });
  await tick();
  assert.equal(local.sent[0].body.context.debug, true);
  const custom = browser({ attrs: { ...ATTRS, "data-endpoint": "https://example.test/", "data-pageviews": "false", "data-version": "2.1" }, ua: "Mozilla/5.0 (iPhone) Mobile Safari" });
  await tick();
  assert.equal(custom.sent.length, 0);
  custom.window.HoldMyIO.page("/manual");
  await tick();
  assert.equal(custom.sent[0].url, "https://example.test/v1/events");
  assert.equal(custom.sent[0].body.context.device_model, "mobile");
  assert.equal(custom.sent[0].body.context.app_version, "2.1");
  assert.equal(custom.sent[0].body.context.debug, false);
});

test("page language, and the current path when no screen is given, through the app's path filter", async () => {
  const b = browser({ href: "https://holdmylemonade.com/reset-password/abcdefghijklmnopqrstuvwxyz", attrs: { ...ATTRS, "data-pageviews": "false" }, lang: "lt" });
  b.window.HoldMyIO.setPathFilter((p) => p.replace(/^\/reset-password\/[^/]+/, "/reset-password/:token"));
  b.window.HoldMyIO.track("client_asset_stale_reload");
  b.window.HoldMyIO.page();
  await tick();
  const [stale, view] = b.events();
  assert.equal(stale.screen, "/reset-password/:token");
  assert.equal(view.screen, "/reset-password/:token");
  assert.equal(stale.context.page_lang, "lt");
  assert.equal(stale.context.locale, "lt-LT");
  assert.ok(!JSON.stringify(b.sent).includes("abcdefghij"));
  // A broken filter falls back to the raw path and never throws into the page
  b.window.HoldMyIO.setPathFilter(() => { throw new Error("x"); });
  b.window.history.pushState({}, "", "/learn/how-to-save");
  await tick();
  assert.equal(b.events().at(-1).screen, "/learn/how-to-save");
});

test("engagement: visible time is sent as ms when the page is hidden; a quick flick is not counted", async () => {
  const clock = { t: 1_800_000_000_000 };
  const b = browser({ attrs: ATTRS, clock });
  clock.t += 45_000;
  b.visibility("hidden");
  b.visibility("hidden"); // already hidden: nothing more
  b.emit("pagehide");
  clock.t += 600_000;
  b.visibility("visible");
  clock.t += 300;
  b.visibility("hidden");
  await tick();
  const engagement = b.events().filter((e) => e.name === "engagement");
  assert.deepEqual(engagement.map((e) => e.props.ms), [45_000]);
  assert.equal(engagement[0].screen, "/pricing");
});
