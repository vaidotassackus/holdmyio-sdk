# Hold My IO SDKs

Client SDKs for [Hold My IO](https://io.holdmy.company), private analytics for iOS, Android and web apps.
Full documentation: **https://io.holdmy.company/docs**

- No IP addresses stored (only the country), no advertising IDs.
- Installation IDs are hashed per app on the server.
- Events queue offline and go out in batches; errors never crash or slow down your app.
- No third-party dependencies.

## iOS (Swift Package Manager)

iOS 15+. In Xcode: File, Add Package Dependencies, `https://github.com/vaidotassackus/holdmyio-sdk`, product `HoldMyIO`.

```swift
import HoldMyIO

HoldMyIO.configure(appKey: "pk_live_…", endpoint: URL(string: "https://io.holdmy.company")!)
HoldMyIO.track("video_exported", props: ["mode": "montage"])
HoldMyIO.screen("editor")
HoldMyIO.purchase(productId: "pro_lifetime", price: 19.99, currency: "EUR")
HoldMyIO.setEnabled(false) // opt-out: nothing is collected, unsent events are deleted
```

## Android

minSdk 24. Download the AAR from https://io.holdmy.company/sdk/holdmyio-android-0.2.0.aar into `app/libs/`
(or build it with `sdk-android/build-aar.sh`):

```kotlin
dependencies { implementation(files("libs/holdmyio-android-0.2.0.aar")) }

// Application.onCreate()
HoldMyIO.configure(this, appKey = "pk_live_…", endpoint = "https://io.holdmy.company")
HoldMyIO.track("video_exported", mapOf("mode" to "montage"))
```

## Web

```html
<script src="https://io.holdmy.company/sdk/holdmyio.js" data-key="pk_live_…" defer></script>
```

Without consent nothing is stored in the browser. `HoldMyIO.consent(true)` keeps a random ID for returning visitors.

## Layout

| Folder | What |
|---|---|
| `Package.swift`, `sdk-swift/` | Swift package `HoldMyIO` |
| `sdk-android/` | Kotlin library `com.holdmyio.HoldMyIO` |
| `sdk-web/` | `holdmyio.js` |

This repository is published from the Hold My IO source tree; issues and questions are welcome.

## License

MIT, see [LICENSE](LICENSE).
