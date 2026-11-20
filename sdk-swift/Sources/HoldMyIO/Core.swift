import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Queue, sessions, disk persistence and sending. All state is mutated only on `queue`.
final class Core: @unchecked Sendable {
    /// Sends the request and returns the HTTP status (nil on a network error).
    typealias Transport = (URLRequest, @escaping (Int?) -> Void) -> Void

    static let sdkVersion = "0.2.0"

    private static let session = URLSession(configuration: .ephemeral)
    static let defaultTransport: Transport = { request, done in
        session.dataTask(with: request) { _, response, _ in
            done((response as? HTTPURLResponse)?.statusCode)
        }.resume()
    }

    private let queue = DispatchQueue(label: "holdmyio.core")
    private let appKey: String
    private let endpoint: URL
    private let options: HoldMyIO.Options
    private let fileURL: URL
    private let transport: Transport
    private let now: () -> Date

    private var state: PersistedState
    private var sessionId = ""
    private var lastActivity: Date?
    private var currentScreen = ""
    /// Whether the first activation (launch) has already been recorded.
    private var hasActivated = false
    private var sending = false
    private var persistScheduled = false
    private var failures = 0
    private var retryAfter = Date.distantPast
    private var timer: DispatchSourceTimer?
    private var observers: [NSObjectProtocol] = []

    init(
        appKey: String,
        endpoint: URL,
        options: HoldMyIO.Options,
        directory: URL? = nil,
        transport: @escaping Transport = Core.defaultTransport,
        now: @escaping () -> Date = Date.init
    ) {
        self.appKey = appKey
        self.endpoint = endpoint
        self.options = options
        self.transport = transport
        self.now = now

        let base = directory
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let safeKey = String(appKey.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
        let file = base
            .appendingPathComponent("HoldMyIO", isDirectory: true)
            .appendingPathComponent("state-\(safeKey).json")
        fileURL = file

        if let data = try? Data(contentsOf: file),
           let saved = try? JSONDecoder().decode(PersistedState.self, from: data) {
            state = saved
        } else {
            state = PersistedState(installId: UUID().uuidString, enabled: true, queue: [])
        }
    }

    deinit {
        timer?.cancel()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    // MARK: - Startup

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            self.persistNow() // so the install ID survives even if the app is closed right away

            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + self.options.flushInterval, repeating: self.options.flushInterval)
            timer.setEventHandler { [weak self] in self?.flushLocked() }
            timer.resume()
            self.timer = timer

            self.flushLocked() // send whatever is left from the previous run
        }

        #if canImport(UIKit) && !os(watchOS)
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.appDidBecomeActive()
        })
        observers.append(center.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.appWillEnterForeground()
        })
        observers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.flush(force: true)
        })
        #endif
    }

    // MARK: - App opens

    /// `app_open` is a launch or a return from the background. `didBecomeActive` also fires after
    /// a system dialog is dismissed (permissions, Control Center), so it only counts the first time.
    /// When launched in the background (background fetch) and opened later, `willEnterForeground` comes first.
    func appDidBecomeActive() {
        let date = now()
        queue.async {
            guard !self.hasActivated else { return }
            self.hasActivated = true
            self.recordLocked(name: "app_open", at: date)
        }
    }

    func appWillEnterForeground() {
        let date = now()
        queue.async {
            self.hasActivated = true
            self.recordLocked(name: "app_open", at: date)
        }
    }

    // MARK: - Events

    func record(
        name: String,
        props: [String: MetricValue] = [:],
        revenue: Revenue? = nil,
        setScreen: String? = nil
    ) {
        let date = now() // timestamp is taken at call time, not when the queue gets to it
        queue.async {
            self.recordLocked(name: name, at: date, props: props, revenue: revenue, setScreen: setScreen)
        }
    }

    /// Only from `queue`.
    private func recordLocked(
        name: String, at date: Date, props: [String: MetricValue] = [:],
        revenue: Revenue? = nil, setScreen: String? = nil
    ) {
        guard state.enabled else { return }
        if let setScreen { currentScreen = setScreen }
        touchSession(at: date)
        append(makeEvent(name: name, at: date, props: props, revenue: revenue))
    }

    private func makeEvent(
        name: String, at date: Date, props: [String: MetricValue] = [:], revenue: Revenue? = nil
    ) -> Event {
        Event(
            id: UUID().uuidString,
            name: name,
            ts: Int64((date.timeIntervalSince1970 * 1000).rounded()),
            sessionId: sessionId,
            screen: currentScreen,
            props: props,
            revenue: revenue
        )
    }

    /// A new session starts after `sessionTimeout` of inactivity.
    private func touchSession(at date: Date) {
        if let last = lastActivity, !sessionId.isEmpty,
           date.timeIntervalSince(last) < options.sessionTimeout {
            lastActivity = date
            return
        }
        sessionId = UUID().uuidString
        lastActivity = date
        append(makeEvent(name: "session_start", at: date))
    }

    private func append(_ event: Event) {
        state.queue.append(event)
        let overflow = state.queue.count - options.maxQueueSize
        if overflow > 0 { state.queue.removeFirst(overflow) } // oldest are dropped first
        schedulePersist()
        if state.queue.count >= options.flushAt { flushLocked() }
    }

    // MARK: - Sending

    func flush(force: Bool = false) {
        queue.async {
            if force { self.retryAfter = .distantPast }
            self.persistNow()
            self.flushLocked()
        }
    }

    private func flushLocked() {
        guard !sending, !state.queue.isEmpty, now() >= retryAfter else { return }

        let batch = Array(state.queue.prefix(options.batchSize))
        let ids = Set(batch.map(\.id))
        guard let body = try? JSONEncoder().encode(Batch(context: makeContext(), events: batch)) else {
            state.queue.removeAll { ids.contains($0.id) } // a batch that cannot be encoded will never succeed
            return
        }

        var request = URLRequest(url: endpoint.appendingPathComponent("v1/events"))
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(appKey, forHTTPHeaderField: "X-App-Key")
        request.httpBody = body

        sending = true
        transport(request) { status in
            self.queue.async {
                self.sending = false
                let code = status ?? 0
                let delivered = (200..<300).contains(code)
                let rejectedForGood = code == 400 || code == 413
                if delivered || rejectedForGood {
                    self.state.queue.removeAll { ids.contains($0.id) }
                    self.failures = 0
                    self.retryAfter = .distantPast
                    self.persistNow()
                    self.flushLocked() // in case more than one batch is queued
                } else {
                    // Network or server error: keep in the queue, wait longer after each attempt.
                    // The server removes duplicates by event id.
                    self.failures += 1
                    let delay = min(3600, 30 * pow(2, Double(self.failures - 1)))
                    self.retryAfter = self.now().addingTimeInterval(delay)
                }
            }
        }
    }

    // MARK: - Opt-out

    func setEnabled(_ enabled: Bool) {
        queue.async {
            self.state.enabled = enabled
            if !enabled { self.state.queue.removeAll() }
            self.persistNow()
        }
    }

    var isEnabled: Bool { queue.sync { state.enabled } }

    // MARK: - Disk

    private func schedulePersist() {
        guard !persistScheduled else { return }
        persistScheduled = true
        queue.asyncAfter(deadline: .now() + 1) {
            self.persistScheduled = false
            self.persistNow()
        }
    }

    private func persistNow() {
        do {
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(state).write(to: fileURL, options: .atomic)
            // Excluded from backups: restoring to a new device yields a new install ID
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var url = fileURL
            try url.setResourceValues(values)
        } catch {
            // Analytics must never crash the app; we will try again next time
        }
    }

    // MARK: - Context

    private func makeContext() -> Context {
        let info = Bundle.main.infoDictionary
        let os = ProcessInfo.processInfo.operatingSystemVersion
        return Context(
            installId: state.installId,
            appVersion: info?["CFBundleShortVersionString"] as? String ?? "",
            build: info?["CFBundleVersion"] as? String ?? "",
            platform: Core.platform,
            osVersion: "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)",
            deviceModel: Core.deviceModel,
            locale: Locale.current.identifier,
            sdkVersion: Core.sdkVersion,
            debug: options.debug
        )
    }

    private static let platform: String = {
        #if os(iOS)
        return "ios"
        #elseif os(macOS)
        return "macos"
        #elseif os(tvOS)
        return "tvos"
        #else
        return "other"
        #endif
    }()

    private static let deviceModel: String = {
        var info = utsname()
        uname(&info)
        return Mirror(reflecting: info.machine).children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(bitPattern: value))))
        }
    }()

    // MARK: - For tests

    /// Waits until queued work has finished.
    func drain() { queue.sync {} }

    var queuedEvents: [Event] { queue.sync { state.queue } }
    var installId: String { queue.sync { state.installId } }
}
