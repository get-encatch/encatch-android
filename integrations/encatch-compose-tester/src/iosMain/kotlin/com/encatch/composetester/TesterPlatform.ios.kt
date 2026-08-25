package com.encatch.composetester

import platform.Foundation.NSBundle

actual val testerPlatformName: String = "iOS"

// Injected into the wrapper app's Info.plist at build time (see the compose-tester-ios
// project.yml and scripts/install-testers-on-device.sh) — empty when not injected.
actual val devDefaultApiKey: String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("EncatchDevApiKey") as? String ?: ""
actual val devDefaultFormId: String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("EncatchDevFormId") as? String ?: ""
