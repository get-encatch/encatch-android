package com.encatch.iosnativeui

import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * `EncatchInternalEmitter.emit(...)` runs on whichever thread the triggering Kotlin coroutine
 * happens to be on (`:core`'s internal scope uses `Dispatchers.Default`) — never assume main
 * thread before touching UIKit/WebKit in a listener closure. Mirrors the same fix applied to
 * `swift/`'s `EncatchFormHost`/`EncatchInlineFormView` after a real crash was found there.
 */
internal fun runOnMain(block: () -> Unit) {
    if (NSThread.isMainThread()) {
        block()
    } else {
        dispatch_async(dispatch_get_main_queue(), block)
    }
}

/** Kotlin/Native port of `swift/`'s `UIApplication.topmostViewController()` extension. */
internal fun topmostViewController(): UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == platform.UIKit.UISceneActivationStateForegroundActive }
    val root = scene?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
        ?.rootViewController
        ?: return null
    var top = root
    while (true) {
        top = top.presentedViewController ?: break
    }
    return top
}
