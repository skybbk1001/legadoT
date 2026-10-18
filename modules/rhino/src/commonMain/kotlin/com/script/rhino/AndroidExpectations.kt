package com.script.rhino

/**
 * android 平台差异点, 由 androidMain 提供实现:
 * - android.content.Context 的类引用与实例判断(脚本屏蔽列表使用)
 * - Build.VERSION.SDK_INT >= O 判断(minSdk 26 恒真, 保留原语义不做常量折叠)
 */
internal expect val androidContextClass: Class<*>

internal expect fun isAndroidContext(obj: Any): Boolean

internal expect fun isSdkAtLeastO(): Boolean
