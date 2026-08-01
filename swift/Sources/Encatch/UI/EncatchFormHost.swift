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
    }
}
