import Foundation
#if canImport(UIKit)
import UIKit
#endif

let SDK_VERSION = "0.1.0"
let SDK_PLATFORM = "ios"

/// Platform-sourced device facts, assembled into `ApiDeviceInfo` by `Encatch`.
struct PlatformDeviceFacts {
    let osVersion: String
    let deviceLocale: String
    let timezone: String?
    let appVersion: String?
    let appPackageName: String?
}

func collectPlatformDeviceFacts() -> PlatformDeviceFacts {
    let bundle = Bundle.main
    let appVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String
    let appPackageName = bundle.bundleIdentifier

    #if canImport(UIKit) && !os(watchOS)
    let osVersion = UIDevice.current.systemVersion
    #else
    let osVersion = ProcessInfo.processInfo.operatingSystemVersionString
    #endif

    return PlatformDeviceFacts(
        osVersion: osVersion,
        deviceLocale: Locale.current.identifier,
        timezone: TimeZone.current.identifier,
        appVersion: appVersion,
        appPackageName: appPackageName
    )
}
