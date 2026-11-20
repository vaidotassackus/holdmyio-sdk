#if canImport(StoreKit)
import StoreKit

@available(iOS 16.0, macOS 13.0, tvOS 16.0, *)
public extension HoldMyIO {
    /// Records a verified StoreKit 2 purchase.
    ///
    ///     if case .success(.verified(let transaction)) = try await product.purchase() {
    ///         HoldMyIO.purchase(transaction)
    ///         await transaction.finish()
    ///     }
    ///
    /// Revoked (refunded) purchases are skipped. The price is taken as the device sees it,
    /// so subscription renewals that happen while the app is not running are not included.
    static func purchase(_ transaction: StoreKit.Transaction, props: [String: MetricValue] = [:]) {
        guard transaction.revocationDate == nil,
              let price = transaction.price,
              let currency = transaction.currency?.identifier
        else { return }
        purchase(productId: transaction.productID, price: price, currency: currency, props: props)
    }
}
#endif
