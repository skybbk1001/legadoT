package io.legado.app.help.http

import android.net.http.X509TrustManagerExtensions

private val unsafeTrustManagerExtensionsDelegate = lazy {
    X509TrustManagerExtensions(SSLHelper.unsafeTrustManager)
}

val SSLHelper.unsafeTrustManagerExtensions: X509TrustManagerExtensions
    get() = unsafeTrustManagerExtensionsDelegate.value
