package io.legado.app.help.http

import io.legado.app.constant.SharedLog

object OkhttpUncaughtExceptionHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        SharedLog.put("Okhttp Dispatcher中的线程执行出错\n${e.localizedMessage}", e)
    }

}
