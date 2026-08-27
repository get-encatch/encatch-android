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
                    // The host app may trigger showForm right as it dismisses its own sheet
                    // (e.g. an "apply & show form" flow) — during that transition the topmost
                    // controller is mid-dismissal or still has a presented child, and UIKit
                    // silently drops a present() aimed at it. Wait for a stable presenter
                    // (bounded retries) instead of presenting into the transition.
                    func presentWhenStable(retriesLeft: Int = 10) {
                        guard let presenter = UIApplication.topmostViewController() else { return }
                        let transitioning = presenter.isBeingDismissed
                            || presenter.presentedViewController != nil
                            || presenter.view.window == nil
                        if transitioning, retriesLeft > 0 {
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                presentWhenStable(retriesLeft: retriesLeft - 1)
                            }
                            return
                        }
                        let controller = EncatchFormViewController()
                        currentController = controller
                        controller.present(payload: payload, from: presenter)
                    }
                    let show = { presentWhenStable() }
                    // If a previous form is still presented (including mid exit-animation),
                    // resolve the presenter only AFTER its dismissal completes. Presenting
                    // immediately would resolve topmostViewController() to the dismissing
                    // controller, and UIKit silently drops a present() from a view controller
                    // that's being dismissed — leaving the SDK wedged with formVisible=true
                    // and nothing on screen.
                    if let old = currentController, old.presentingViewController != nil {
                        currentController = nil
                        old.dismiss(animated: false, completion: show)
                    } else {
                        show()
                    }
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
