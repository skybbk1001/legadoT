package io.legado.app.constant

object SharedLog {

    var isDebug: Boolean = false

    var put: (message: String, throwable: Throwable?) -> Unit = { _, _ -> }

}
