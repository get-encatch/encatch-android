package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates a main-thread-confined [CoroutineScope] for a UI layer (`:android`'s dialogs/views
 * build their own via `CoroutineScope(SupervisorJob() + Dispatchers.Main)` directly since Kotlin
 * is native there) to drive a [FormWebViewBridge]. Exposed here because Swift/Kotlin-Native
 * consumers have no other way to construct a [CoroutineScope] — they can't implement the
 * `CoroutineScope` interface themselves the way a Kotlin caller can.
 */
fun createUiCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
