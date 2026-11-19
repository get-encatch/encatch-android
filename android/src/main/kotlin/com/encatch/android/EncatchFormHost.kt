package com.encatch.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import com.encatch.core.EncatchContextInitializer
import com.encatch.core.Encatch
import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.InternalEvent
import com.encatch.core.PendingCompletionCtaScheduler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the Encatch form UI at the application root, mirroring how `EncatchProvider`/
 * `EncatchWebView` mount once in the RN SDK's component tree.
 *
 * Installed automatically at process start by [EncatchFormHostInitializer] (AndroidX App Startup,
 * before `Application.onCreate`), so Native Android, KMP and Compose apps on Android need no code
 * for it. Calling [install] yourself (as earlier versions asked) still works: every call after the
 * first is a no-op, so listeners are registered once and one survey opens one dialog.
 *
 * Tracks the current foreground [Activity] to host the modal form overlay, and wires
 * [Encatch.pendingCtaScheduler] plus retry-queue-flush-on-foreground via [ProcessLifecycleOwner].
 */
object EncatchFormHost {
    private val installed = AtomicBoolean(false)
    @Volatile private var currentActivity: Activity? = null
    private var currentDialog: EncatchFormDialog? = null

    /** Whether the host is installed (by [EncatchFormHostInitializer] or an [install] call). */
    val isInstalled: Boolean get() = installed.get()

    /**
     * Installs the host. Idempotent and thread-safe: only the first call registers anything, and it
     * does so on the main thread (posted there when called from another thread). Normally done for
     * you at process start; call it only if you removed AndroidX App Startup's
     * `InitializationProvider` from your manifest.
     */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installOnMain(application)
        } else {
            Handler(Looper.getMainLooper()).post { installOnMain(application) }
        }
    }

    private fun installOnMain(application: Application) {

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    currentActivity = activity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (currentActivity === activity) currentActivity = null
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivity === activity) currentActivity = null
                    if (currentDialog?.context === activity) {
                        currentDialog?.dismiss()
                        currentDialog = null
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            },
        )

        Encatch.pendingCtaScheduler = PendingCompletionCtaScheduler(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
            redirectOpener = RedirectBrowser(application),
            emitEvent = { type, payload -> Encatch.emitEvent(type, payload) },
            openExternal = { url -> RedirectBrowser(application).openExternal(url) },
        )

        // Retry-queue flush on foreground. Best effort: ProcessLifecycleOwner is set up by its own
        // App Startup initializer, which an app may have removed — the host must work without it.
        // An observer added before that initializer runs still gets its events once it does.
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        Encatch.flushRetryQueue()
                    }
                },
            )
        }

        // EncatchInternalEmitter.emit(...) runs on whichever thread triggered it — automatic
        // triggers (track-screen/track-event/identify auto-shows) emit from core's
        // Dispatchers.Default scope, so never assume main thread before touching the Dialog /
        // WebView. Mirrors EncatchFormHost.swift's DispatchQueue.main marshaling; without it
        // the Dialog constructor throws off-main and core's runCatching swallows it — the
        // modal silently never appears.
        val mainHandler = Handler(Looper.getMainLooper())
        EncatchInternalEmitter.on { event ->
            mainHandler.post {
                when (event) {
                    is InternalEvent.ShowForm -> {
                        // inline shows belong to an inline slot, not this host
                        if (event.payload.presentation != "modal") return@post
                        // no activity to show it in: the survey is not open and must not hold the one-at-a-time guard
                        val activity = currentActivity ?: run {
                            Encatch.setFormVisible(false, event.payload.formId)
                            return@post
                        }
                        currentDialog?.dismiss()
                        val dialog = EncatchFormDialog(activity)
                        currentDialog = dialog
                        dialog.present(event.payload)
                    }
                    is InternalEvent.DismissForm -> {
                        currentDialog?.dismiss()
                        currentDialog = null
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Installs the host (if needed) and, when [activity] is already resumed and the host knows no
     * screen yet, uses it as the screen to show surveys on. For a host installed late — after the
     * screen resumed, so it missed `onActivityResumed` (e.g. App Startup disabled and the host
     * installed from a composable) — a survey asked for right away still opens.
     */
    fun attach(activity: Activity) {
        install(activity.application)
        val runOnMain = Runnable {
            if (currentActivity == null && isResumed(activity)) currentActivity = activity
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runOnMain.run()
        else Handler(Looper.getMainLooper()).post(runOnMain)
    }

    /** [attach] for any [Context]: unwraps it to its [Activity]; otherwise installs from the application. */
    fun attach(context: Context) {
        val activity = context.findActivity()
        if (activity != null) {
            attach(activity)
        } else {
            (context.applicationContext as? Application)?.let { install(it) }
        }
    }

    private fun isResumed(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val owner = activity as? LifecycleOwner
            ?: return activity.hasWindowFocus()
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext?.findActivity()
        else -> null
    }
}

/**
 * Installs [EncatchFormHost] at process start via AndroidX App Startup (registered in this
 * library's AndroidManifest.xml, merged into the app's `InitializationProvider` next to core's
 * [EncatchContextInitializer]). Runs before `Application.onCreate` and before any Activity is
 * created, so the host sees the first screen resume.
 */
class EncatchFormHostInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        (context.applicationContext as? Application)?.let { EncatchFormHost.install(it) }
    }

    // Not ProcessLifecycleInitializer: depending on it throws ("cannot be initialized lazily")
    // when an app removed its manifest entry or runs this initializer through AppInitializer.
    // install() copes with ProcessLifecycleOwner starting after it.
    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(EncatchContextInitializer::class.java)
}
