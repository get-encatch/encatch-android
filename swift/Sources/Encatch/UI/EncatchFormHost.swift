import EncatchCore
import UIKit

/// Installs the Encatch modal form UI at the app root, mirroring `:android`'s `EncatchFormHost`.
/// Call `EncatchFormHost.install()` once, typically from `application(_:didFinishLaunchingWithOptions:)`.
///
/// Subscribes to `EncatchInternalEmitter` for `ShowForm`(presentation != "inline")/`DismissForm`
/// and presents/dismisses `EncatchFormViewController` on the topmost view controller.
public enum EncatchFormHost {
    private static var installed = false
    private static var currentController: EncatchFormViewController?

    public static func install() {
        guard !installed else { return }
        installed = true

        _ = EncatchInternalEmitter.shared.on { event in
            // EncatchInternalEmitter.emit(...) runs on whichever thread the Kotlin coroutine
            // that triggered it happens to be on (:core's internal scope uses
            // Dispatchers.Default) — never assume main thread before touching UIKit/WebKit.
            guard let event else { return }
            let work = {
                switch event {
                case let showForm as InternalEvent.ShowForm:
                    guard showForm.payload.presentation != "inline" else { return }
                    guard let presenter = UIApplication.topmostViewController() else { return }
                    currentController?.dismiss(animated: false)
                    let controller = EncatchFormViewController()
                    currentController = controller
                    controller.present(payload: showForm.payload, from: presenter)
                case is InternalEvent.DismissForm:
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
