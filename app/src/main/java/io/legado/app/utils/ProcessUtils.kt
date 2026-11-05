package io.legado.app.utils

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

const val WEB_LOGIN_PROCESS_SUFFIX = ":web_login"

fun Context.isWebLoginProcess(): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
    }
    return processName == "$packageName$WEB_LOGIN_PROCESS_SUFFIX"
}

/** WebView.setDataDirectorySuffix 从 Android 9 开始可用。 */
fun isolatedWebLoginProcessSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
