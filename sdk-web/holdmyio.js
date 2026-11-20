/*! Hold My IO web SDK 0.3.0 | https://io.holdmy.company */
// Įdėjimas:
//   <script src="https://io.holdmy.company/sdk/holdmyio.js" data-key="pk_live_..." defer></script>
// Be sutikimo naršyklėje nesaugoma nieko: įvykiai siunčiami su `anonymous: true`, o serveris lankytoją
// atpažįsta tik tą pačią dieną (hash su kasdien keičiamu salt). Lankytojui sutikus su statistika:
//   HoldMyIO.consent(true)   // atsitiktinis ID localStorage: matosi grįžimai, retention, piltuvėliai
//   HoldMyIO.consent(false)  // ID ištrinamas, vėl anoniminis režimas
// Kiti įvykiai: HoldMyIO.track("signup", { plan: "pro" }). Puslapiai fiksuojami automatiškai,
// taip pat SPA naršymas (history API). data-spa="false" išjungia, tada HoldMyIO.page() rankiniu būdu.
(function (global) {
  "use strict";
  var VERSION = "0.3.0";
  var ID_KEY = "holdmyio_id";
  var SESSION_KEY = "holdmyio_session";
  var SESSION_TIMEOUT = 30 * 60 * 1000;

  var doc = global.document;
  var nav = global.navigator || {};
  var cfg = null;
  var queue = [];
  var timer = null;
  var currentScreen = "";
  var lastPath = null;
  var firstPage = true;

  function uuid() {
    if (global.crypto && global.crypto.randomUUID) return global.crypto.randomUUID();
    var b = new Uint8Array(16);
    global.crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = Array.prototype.map.call(b, function (x) { return (x + 0x100).toString(16).slice(1); }).join("");
    return h.slice(0, 8) + "-" + h.slice(8, 12) + "-" + h.slice(12, 16) + "-" + h.slice(16, 20) + "-" + h.slice(20);
  }

  function storage(kind) {
    try { return global[kind] || null; } catch (e) { return null; }
  }

  function storedId() {
    var ls = storage("localStorage");
    try { return ls ? ls.getItem(ID_KEY) : null; } catch (e) { return null; }
  }

  /** Sesija tik su sutikimu: sessionStorage, nauja po 30 min. neveiklumo. */
  function sessionId(now) {
    var ss = storage("sessionStorage");
    if (!ss) return "";
    try {
      var saved = JSON.parse(ss.getItem(SESSION_KEY) || "null");
      var id = saved && now - saved.at < SESSION_TIMEOUT ? saved.id : uuid();
      ss.setItem(SESSION_KEY, JSON.stringify({ id: id, at: now }));
      return id;
    } catch (e) {
      return "";
    }
  }

  function deviceType() {
    var ua = nav.userAgent || "";
    if (/iPad|Tablet/i.test(ua) || (/Android/i.test(ua) && !/Mobile/i.test(ua))) return "tablet";
    if (/Mobi|iPhone|Android/i.test(ua)) return "mobile";
    return "desktop";
  }

  function context() {
    var id = storedId();
    var ctx = {
      platform: "web",
      app_version: cfg.version || "",
      device_model: deviceType(),
      locale: (nav.language || "").slice(0, 16),
      sdk_version: VERSION,
      debug: !!cfg.debug,
    };
    if (id) ctx.install_id = id; else ctx.anonymous = true;
    return ctx;
  }

  function send() {
    timer = null;
    if (!queue.length || !cfg) return;
    var events = queue.splice(0, 50);
    var body = JSON.stringify({ context: context(), events: events });
    try {
      global.fetch(cfg.endpoint + "/v1/events", {
        method: "POST",
        keepalive: true,
        headers: { "content-type": "application/json", "x-app-key": cfg.key },
        body: body,
      }).catch(function () {});
    } catch (e) { /* analitika niekada nenulaužia puslapio */ }
    if (queue.length) send();
  }

  function schedule() {
    if (!timer) timer = global.setTimeout(send, 50);
  }

  function clean(props) {
    var out = {};
    if (!props || typeof props !== "object") return out;
    for (var k in props) {
      var v = props[k];
      if (typeof v === "string" || typeof v === "boolean" || (typeof v === "number" && isFinite(v))) out[k] = v;
    }
    return out;
  }

  function track(name, props, screen) {
    if (!cfg || cfg.disabled) return;
    var now = Date.now();
    if (screen !== undefined) currentScreen = screen;
    queue.push({
      id: uuid(),
      name: String(name).slice(0, 64),
      ts: now,
      session_id: storedId() ? sessionId(now) : "",
      screen: currentScreen,
      props: clean(props),
    });
    schedule();
  }

  /** Puslapio peržiūra. Kelias be užklausos ir # (juose būna asmens duomenų). */
  function page(path) {
    var loc = global.location;
    var p = path || loc.pathname || "/";
    if (p === lastPath && !path) return;
    lastPath = p;
    var props = {};
    if (firstPage) {
      firstPage = false;
      try {
        var ref = doc.referrer ? new URL(doc.referrer).host : "";
        if (ref && ref !== loc.host) props.referrer = ref;
      } catch (e) {}
      try {
        var q = new URLSearchParams(loc.search || "");
        ["utm_source", "utm_medium", "utm_campaign"].forEach(function (k) {
          var v = q.get(k);
          if (v) props[k] = v.slice(0, 100);
        });
      } catch (e) {}
    }
    track("screen_view", props, p);
  }

  /** Lankytojo sutikimas su statistika (pvz. iš cookie banerio). */
  function consent(granted) {
    var ls = storage("localStorage"), ss = storage("sessionStorage");
    try {
      if (granted) {
        if (ls && !ls.getItem(ID_KEY)) ls.setItem(ID_KEY, uuid().toUpperCase());
      } else {
        if (ls) ls.removeItem(ID_KEY);
        if (ss) ss.removeItem(SESSION_KEY);
      }
    } catch (e) {}
  }

  function watchHistory() {
    var h = global.history;
    if (!h || !h.pushState) return;
    ["pushState", "replaceState"].forEach(function (m) {
      var orig = h[m];
      h[m] = function () {
        var r = orig.apply(this, arguments);
        page();
        return r;
      };
    });
    global.addEventListener("popstate", function () { page(); });
  }

  function init(options) {
    if (cfg) return;
    var o = options || {};
    if (!o.key) return;
    var host = (global.location && global.location.hostname) || "";
    cfg = {
      key: o.key,
      endpoint: (o.endpoint || "https://io.holdmy.company").replace(/\/+$/, ""),
      version: o.version || "",
      debug: o.debug != null ? !!o.debug : /^(localhost|127\.0\.0\.1|\[::1\])$|\.(local|test|localhost)$/.test(host),
      disabled: !!nav.webdriver && !o.debug,
    };
    global.addEventListener("pagehide", send);
    doc.addEventListener("visibilitychange", function () { if (doc.visibilityState === "hidden") send(); });
    if (o.spa !== false) watchHistory();
    if (o.pageviews !== false) page();
  }

  var api = { init: init, page: page, track: track, consent: consent, flush: send, version: VERSION };
  global.HoldMyIO = api;

  // Automatinis paleidimas iš <script data-key=...>
  var script = doc.currentScript;
  if (script && script.getAttribute("data-key")) {
    var src = script.getAttribute("src") || "";
    var origin = "";
    try { origin = new URL(src, global.location.href).origin; } catch (e) {}
    init({
      key: script.getAttribute("data-key"),
      endpoint: script.getAttribute("data-endpoint") || origin || undefined,
      version: script.getAttribute("data-version") || "",
      debug: script.hasAttribute("data-debug") ? script.getAttribute("data-debug") !== "false" : undefined,
      spa: script.getAttribute("data-spa") !== "false",
      pageviews: script.getAttribute("data-pageviews") !== "false",
    });
  }
})(typeof window !== "undefined" ? window : this);
