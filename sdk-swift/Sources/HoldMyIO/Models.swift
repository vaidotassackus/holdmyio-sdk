import Foundation

/// Įvykio savybės reikšmė: tekstas, skaičius arba bool.
/// Galima rašyti tiesiog literalais: `["source": "push", "count": 3, "first": true]`.
public enum MetricValue: Codable, Equatable, Sendable,
    ExpressibleByStringInterpolation, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByBooleanLiteral
{
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)

    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .int(value) }
    public init(floatLiteral value: Double) { self = .double(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else {
            self = .string(try container.decode(String.self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        }
    }
}

struct Revenue: Codable, Equatable {
    let amount: Double
    let currency: String
    let productId: String

    enum CodingKeys: String, CodingKey {
        case amount, currency
        case productId = "product_id"
    }
}

struct Event: Codable, Equatable {
    let id: String
    let name: String
    /// Milisekundės nuo 1970-01-01 UTC
    let ts: Int64
    let sessionId: String
    let screen: String
    let props: [String: MetricValue]
    let revenue: Revenue?

    enum CodingKeys: String, CodingKey {
        case id, name, ts, screen, props, revenue
        case sessionId = "session_id"
    }
}

struct Context: Encodable {
    let installId: String
    let appVersion: String
    let build: String
    let platform: String
    let osVersion: String
    let deviceModel: String
    let locale: String
    let sdkVersion: String
    let debug: Bool

    enum CodingKeys: String, CodingKey {
        case platform, locale, debug, build
        case installId = "install_id"
        case appVersion = "app_version"
        case osVersion = "os_version"
        case deviceModel = "device_model"
        case sdkVersion = "sdk_version"
    }
}

struct Batch: Encodable {
    let context: Context
    let events: [Event]
}

/// Tai, kas išlieka diske tarp paleidimų.
struct PersistedState: Codable {
    var installId: String
    var enabled: Bool
    var queue: [Event]
}
