package io.legado.app.utils

import io.legado.app.constant.SharedLog

fun Throwable.printOnDebug() {
    if (SharedLog.isDebug) {
        printStackTrace()
    }
}
