package com.encatch.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.encatch.core.Encatch
import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.InternalEvent
import com.encatch.core.PendingCompletionCtaScheduler

/**
 * Installs the Encatch form UI at the application root, mirroring how `EncatchProvider`/
 * `EncatchWebView` mount once in the RN SDK's component tree. Call [install] once, typically
 * from [Application.onCreate].
 *
 * Tracks the current foreground [Activity] to host the modal form overlay, and wires
 * [Encatch.pendingCtaScheduler] plus retry-queue-flush-on-foreground via [ProcessLifecycleOwner].
 */
object EncatchFormHost {
    private var installed = false
    private var currentActivity: Activity? = null
    private var currentDialog: EncatchFormDialog? = null

    fun install(application: Application) {
        if (installed) return
        installed = true

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    currentActivity = activity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (currentActivity === activity) currentActivity = null
                }

                override fun onActivityDestroyed(activity: Activity) {
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

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Encatch.flushRetryQueue()
                }
            },
        )

        EncatchInternalEmitter.on { event ->
            when (event) {
                is InternalEvent.ShowForm -> {
                    val activity = currentActivity ?: return@on
                    if (event.payload.presentation != "modal") return@on
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
