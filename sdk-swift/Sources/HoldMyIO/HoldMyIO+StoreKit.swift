#if canImport(StoreKit)
import StoreKit

@available(iOS 16.0, macOS 13.0, tvOS 16.0, *)
public extension HoldMyIO {
    /// Užregistruoja patvirtintą StoreKit 2 pirkimą.
    ///
    ///     if case .success(.verified(let transaction)) = try await product.purchase() {
    ///         HoldMyIO.purchase(transaction)
    ///         await transaction.finish()
    ///     }
    ///
    /// Atšaukti (refund) pirkimai praleidžiami. Kaina imama tokia, kokią mato įrenginys,
    /// todėl prenumeratų atnaujinimai, įvykę appsui neveikiant, čia nepatenka.
    static func purchase(_ transaction: StoreKit.Transaction, props: [String: MetricValue] = [:]) {
        guard transaction.revocationDate == nil,
              let price = transaction.price,
              let currency = transaction.currency?.identifier
        else { return }
        purchase(productId: transaction.productID, price: price, currency: currency, props: props)
    }
}
#endif
