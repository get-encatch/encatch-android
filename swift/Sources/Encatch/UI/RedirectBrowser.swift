import EncatchCore
import SafariServices
import UIKit

/// Opens `redirect_internal` CTA URLs in an in-app Safari view, and `redirect_external` URLs in
/// the system browser — mirrors `:android`'s `RedirectBrowser`.
final class RedirectBrowser: NSObject, RedirectOpener {
    func openInternal(url: String, completionHandler: @escaping ((any Error)?) -> Void) {
        guard let url = URL(string: url) else {
            completionHandler(nil)
            return
        }
        DispatchQueue.main.async {
            let safari = SFSafariViewController(url: url)
            UIApplication.topmostViewController()?.present(safari, animated: true)
            completionHandler(nil)
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
            top = presented
        }
        return top
    }
}
