package io.legado.app.utils

import android.net.NetworkCapabilities
import splitties.systemservices.connectivityManager

internal actual fun isNetworkAvailable(): Boolean {
    val network = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            // 移动数据
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            // 以太网
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            // VPN
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
