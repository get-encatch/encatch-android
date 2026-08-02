#if canImport(UIKit)
import UIKit

/// Installs the Encatch modal form UI at the app root, mirroring `:android`'s `EncatchFormHost`.
/// Call `EncatchFormHost.install()` once, typically from `application(_:didFinishLaunchingWithOptions:)`.
///
/// Subscribes to `EncatchInternalEmitter` for `.showForm`(presentation != "inline")/`.dismissForm`
/// and presents/dismisses `EncatchFormViewController` on the topmost view controller.
public enum EncatchFormHost {
    private static var installed = false
    private static var currentController: EncatchFormViewController?

    public static func install() {
        guard !installed else { return }
        installed = true

        _ = EncatchInternalEmitter.shared.on { event in
            // EncatchInternalEmitter.emit(...) runs on whichever thread triggered it — never
            // assume main thread before touching UIKit/WebKit.
            let work = {
                switch event {
                case .showForm(let payload):
                    guard payload.presentation != "inline" else { return }
                    guard let presenter = UIApplication.topmostViewController() else { return }
                    currentController?.dismiss(animated: false)
                    let controller = EncatchFormViewController()
                    currentController = controller
                    controller.present(payload: payload, from: presenter)
                case .dismissForm:
                    currentController?.dismiss(animated: false)
                    currentController = nil
                default:
                    break
                }
            }
            if Thread.isMainThread {
                work()
            } else {
                DispatchQueue.main.sync(execute: work)
            }
        }
    }
}
#endif
