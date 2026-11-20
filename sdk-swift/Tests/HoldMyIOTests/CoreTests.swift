import XCTest
@testable import HoldMyIO

final class CoreTests: XCTestCase {
    private var directory: URL!

    override func setUp() {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("holdmyio-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
    }

    /// Netikras tinklas: įsimena užklausas ir atsako nurodytu statusu.
    private final class FakeNetwork: @unchecked Sendable {
        var status: Int? = 202
        private(set) var requests: [URLRequest] = []

        lazy var transport: Core.Transport = { [unowned self] request, done in
            self.requests.append(request)
            done(self.status)
        }

        func body(_ index: Int) throws -> [String: Any] {
            let data = try XCTUnwrap(requests[index].httpBody)
            return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        }
    }

    private func makeCore(_ network: FakeNetwork, now: @escaping () -> Date = Date.init) -> Core {
        var options = HoldMyIO.Options()
        options.flushAt = 1000 // testuose siunčiam tik rankiniu flush
        return Core(
            appKey: "pk_test_0123456789abcdef",
            endpoint: URL(string: "https://in.example.com")!,
            options: options,
            directory: directory,
            transport: network.transport,
            now: now
        )
    }

    /// Flush atsakymas grįžta per tą pačią eilę, todėl laukiam du kartus.
    private func settle(_ core: Core) {
        core.drain()
        core.drain()
    }

    func testTrackSendsBatchAndClearsQueue() throws {
        let network = FakeNetwork()
        let core = makeCore(network)

        core.record(name: "screen_view", setScreen: "home")
        core.record(name: "export_done", props: ["format": "reel", "seconds": 42, "hd": true])
        core.record(name: "purchase", revenue: Revenue(amount: 4.99, currency: "EUR", productId: "pro"))
        core.flush()
        settle(core)

        XCTAssertEqual(network.requests.count, 1)
        let request = network.requests[0]
        XCTAssertEqual(request.url?.absoluteString, "https://in.example.com/v1/events")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-App-Key"), "pk_test_0123456789abcdef")

        let body = try network.body(0)
        let context = try XCTUnwrap(body["context"] as? [String: Any])
        XCTAssertEqual(context["install_id"] as? String, core.installId)

        let events = try XCTUnwrap(body["events"] as? [[String: Any]])
        XCTAssertEqual(events.map { $0["name"] as? String },
                       ["session_start", "screen_view", "export_done", "purchase"])
        XCTAssertEqual(events[2]["screen"] as? String, "home")
        let props = try XCTUnwrap(events[2]["props"] as? [String: Any])
        XCTAssertEqual(props["format"] as? String, "reel")
        XCTAssertEqual(props["seconds"] as? Int, 42)
        let revenue = try XCTUnwrap(events[3]["revenue"] as? [String: Any])
        XCTAssertEqual(revenue["product_id"] as? String, "pro")
        XCTAssertEqual(revenue["currency"] as? String, "EUR")
        XCTAssertNil(events[1]["revenue"])

        let sessions = Set(events.compactMap { $0["session_id"] as? String })
        XCTAssertEqual(sessions.count, 1)
        XCTAssertTrue(core.queuedEvents.isEmpty)
    }

    func testFailureKeepsEventsAndSurvivesRestart() {
        let network = FakeNetwork()
        network.status = nil // nėra interneto
        let first = makeCore(network)
        first.record(name: "app_open")
        first.flush()
        settle(first)
        XCTAssertEqual(first.queuedEvents.count, 2) // session_start + app_open
        let installId = first.installId

        network.status = 202
        let second = makeCore(network)
        XCTAssertEqual(second.installId, installId)
        XCTAssertEqual(second.queuedEvents.count, 2)
        second.flush(force: true)
        settle(second)
        XCTAssertTrue(second.queuedEvents.isEmpty)
    }

    func testPoisonBatchIsDropped() {
        let network = FakeNetwork()
        network.status = 400
        let core = makeCore(network)
        core.record(name: "app_open")
        core.flush()
        settle(core)
        XCTAssertTrue(core.queuedEvents.isEmpty)
    }

    func testNewSessionAfterTimeout() throws {
        let network = FakeNetwork()
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        let core = makeCore(network, now: { clock })

        core.record(name: "a")
        core.drain()
        clock.addTimeInterval(31 * 60)
        core.record(name: "b")
        core.drain()

        let events = core.queuedEvents
        XCTAssertEqual(events.map(\.name), ["session_start", "a", "session_start", "b"])
        XCTAssertNotEqual(events[1].sessionId, events[3].sessionId)
    }

    func testAppOpenOnlyOnLaunchAndReturnFromBackground() {
        let core = makeCore(FakeNetwork())
        core.appDidBecomeActive()      // paleidimas
        core.appDidBecomeActive()      // uždarytas leidimų langas: ne atidarymas
        core.appWillEnterForeground()  // grįžo iš fono
        core.appDidBecomeActive()      // po jo visada ateina ir šis
        core.drain()
        XCTAssertEqual(core.queuedEvents.map(\.name).filter { $0 == "app_open" }.count, 2)
    }

    func testAppOpenOnceWhenLaunchedInBackgroundThenOpened() {
        let core = makeCore(FakeNetwork())
        core.appWillEnterForeground()  // paleistas fone (atsisiuntimas), dabar atidarytas
        core.appDidBecomeActive()
        core.drain()
        XCTAssertEqual(core.queuedEvents.map(\.name).filter { $0 == "app_open" }.count, 1)
    }

    func testDisabledCollectsNothing() {
        let network = FakeNetwork()
        let core = makeCore(network)
        core.record(name: "a")
        core.setEnabled(false)
        core.record(name: "b")
        core.flush()
        settle(core)
        XCTAssertTrue(core.queuedEvents.isEmpty)
        XCTAssertTrue(network.requests.isEmpty)
        XCTAssertFalse(core.isEnabled)
    }
}
