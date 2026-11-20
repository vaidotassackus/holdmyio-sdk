import Foundation

/// Public SDK API.
///
///     HoldMyIO.configure(appKey: "pk_live_...", endpoint: URL(string: "https://in.example.com")!)
///     HoldMyIO.screen("editor")
///     HoldMyIO.track("export_done", props: ["format": "reel", "seconds": 42])
///
/// `session_start` and `app_open` are sent automatically. Events are stored on disk and
/// sent in batches, so it works offline too.
public enum HoldMyIO {
    public struct Options: Sendable {
        /// How often (in seconds) to try sending the queue.
        public var flushInterval: TimeInterval = 30
        /// Once this many events are queued, they are sent immediately.
        public var flushAt = 20
        /// Number of events per batch (the server accepts up to 100).
        public var batchSize = 50
        /// Maximum number of events kept on disk while offline.
        public var maxQueueSize = 1000
        /// A new session starts after this many seconds of inactivity.
        public var sessionTimeout: TimeInterval = 30 * 60
        /// Events from debug builds are flagged and excluded from statistics.
        public var debug: Bool = {
            #if DEBUG
            return true
            #else
            return false
            #endif
        }()

        public init() {}
    }

    private static let lock = NSLock()
    private static var core: Core?

    private static var current: Core? {
        lock.lock()
        defer { lock.unlock() }
        return core
    }

    /// Call once at app launch (e.g. `App.init()`), before any other calls.
    public static func configure(appKey: String, endpoint: URL, options: Options = Options()) {
        lock.lock()
        defer { lock.unlock() }
        guard core == nil else { return }
        let created = Core(appKey: appKey, endpoint: endpoint, options: options)
        core = created
        created.start()
    }

    /// Any event. Name: letters, digits, `_` and `.`, up to 64 characters.
    public static func track(_ name: String, props: [String: MetricValue] = [:]) {
        current?.record(name: name, props: props)
    }

    /// A screen view. Later events are attributed to this screen until you call it with another.
    public static func screen(_ name: String, props: [String: MetricValue] = [:]) {
        current?.record(name: "screen_view", props: props, setScreen: name)
    }

    /// A purchase. `currency` is an ISO 4217 code, e.g. "EUR".
    public static func purchase(
        productId: String, price: Decimal, currency: String, props: [String: MetricValue] = [:]
    ) {
        let revenue = Revenue(
            amount: NSDecimalNumber(decimal: price).doubleValue,
            currency: currency.uppercased(),
            productId: productId
        )
        current?.record(name: "purchase", props: props, revenue: revenue)
    }

    /// Sends the queue now, without waiting for the timer.
    public static func flush() {
        current?.flush(force: true)
    }

    /// User opt-out: when disabled nothing is collected and unsent events are deleted.
    public static func setEnabled(_ enabled: Bool) {
        current?.setEnabled(enabled)
    }

    public static var isEnabled: Bool {
        current?.isEnabled ?? false
    }
}
