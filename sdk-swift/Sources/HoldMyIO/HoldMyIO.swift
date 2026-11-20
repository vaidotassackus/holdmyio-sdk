import Foundation

/// Viešas SDK API.
///
///     HoldMyIO.configure(appKey: "pk_live_...", endpoint: URL(string: "https://in.example.com")!)
///     HoldMyIO.screen("editor")
///     HoldMyIO.track("export_done", props: ["format": "reel", "seconds": 42])
///
/// Automatiškai siunčiami `session_start` ir `app_open`. Įvykiai kaupiami diske ir
/// siunčiami paketais, todėl veikia ir be interneto.
public enum HoldMyIO {
    public struct Options: Sendable {
        /// Kas kiek sekundžių bandoma išsiųsti eilę.
        public var flushInterval: TimeInterval = 30
        /// Eilėje susikaupus tiek įvykių, siunčiama iškart.
        public var flushAt = 20
        /// Įvykių skaičius viename pakete (serveris priima iki 100).
        public var batchSize = 50
        /// Daugiausia įvykių, laikomų diske be interneto.
        public var maxQueueSize = 1000
        /// Po tiek sekundžių neveiklumo pradedama nauja sesija.
        public var sessionTimeout: TimeInterval = 30 * 60
        /// Debug buildų įvykiai pažymimi ir į statistiką neįtraukiami.
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

    /// Kviesk vieną kartą paleidžiant appsą (pvz. `App.init()`), prieš kitus kvietimus.
    public static func configure(appKey: String, endpoint: URL, options: Options = Options()) {
        lock.lock()
        defer { lock.unlock() }
        guard core == nil else { return }
        let created = Core(appKey: appKey, endpoint: endpoint, options: options)
        core = created
        created.start()
    }

    /// Bet koks įvykis. Pavadinimas: raidės, skaičiai, `_` ir `.`, iki 64 simbolių.
    public static func track(_ name: String, props: [String: MetricValue] = [:]) {
        current?.record(name: name, props: props)
    }

    /// Ekrano peržiūra. Vėlesni įvykiai priskiriami šiam ekranui, kol nepakviesi su kitu.
    public static func screen(_ name: String, props: [String: MetricValue] = [:]) {
        current?.record(name: "screen_view", props: props, setScreen: name)
    }

    /// Pirkimas. `currency` yra ISO 4217 kodas, pvz. "EUR".
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

    /// Išsiunčia eilę dabar, nelaukiant laikmačio.
    public static func flush() {
        current?.flush(force: true)
    }

    /// Naudotojo atsisakymas: išjungus nieko nerenkama, o neišsiųsti įvykiai ištrinami.
    public static func setEnabled(_ enabled: Bool) {
        current?.setEnabled(enabled)
    }

    public static var isEnabled: Bool {
        current?.isEnabled ?? false
    }
}
