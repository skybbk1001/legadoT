package com.script.rhino

import android.os.Build

internal actual val androidContextClass: Class<*> = android.content.Context::class.java

internal actual fun isAndroidContext(obj: Any): Boolean = obj is android.content.Context

internal actual fun isSdkAtLeastO(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
