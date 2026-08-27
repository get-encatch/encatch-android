#if canImport(UIKit)
import SafariServices
import UIKit

/// Opens `redirect_internal` CTA URLs in an in-app Safari view, and `redirect_external` URLs in
/// the system browser — mirrors `:android`'s `RedirectBrowser`.
final class RedirectBrowser: NSObject, RedirectOpener {
    func openInternal(url: String) async {
        guard let url = URL(string: url) else { return }
        await MainActor.run {
            let safari = SFSafariViewController(url: url)
            UIApplication.topmostViewController()?.present(safari, animated: true)
        }
    }

    func openExternal(_ url: String) {
        guard let url = URL(string: url) else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(url)
        }
    }
}

extension UIApplication {
    static func topmostViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        guard let root = (scene?.windows.first { $0.isKeyWindow })?.rootViewController else { return nil }
        var top = root
        while let presented = top.presentedViewController {
            // A sheet mid-dismissal is still linked as presentedViewController but its view has
            // left the hierarchy — presenting on it is silently dropped by UIKit ("whose view
            // is not in the window hierarchy"). Stop at its presenter instead.
            if presented.isBeingDismissed { break }
            top = presented
        }
        return top
    }
}
#endif
