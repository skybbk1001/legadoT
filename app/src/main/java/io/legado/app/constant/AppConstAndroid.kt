package io.legado.app.constant

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.provider.Settings
import io.legado.app.BuildConfig
import io.legado.app.help.crypto.digest
import io.legado.app.help.crypto.toHexString
import io.legado.app.help.update.AppVariant
import splitties.init.appCtx

private const val OFFICIAL_SIGNATURE =
    "8DACBF25EC667C9B1374DB1450C1A866C2AAA1173016E80BF6AD2F06FABDDC08"
private const val BETA_SIGNATURE =
    "93A28468B0F69E8D14C8A99AB45841CEF902BBBA3761BBFEE02E67CBA801563E"

@Suppress("DEPRECATION")
private val sha256Signature: String by lazy {
    val packageInfo =
        appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_SIGNATURES)
    digest("SHA-256", packageInfo.signatures!![0].toByteArray()).toHexString().uppercase()
}

private val isOfficial = sha256Signature == OFFICIAL_SIGNATURE

private val isBeta = sha256Signature == BETA_SIGNATURE || BuildConfig.DEBUG

@SuppressLint("PrivateResource")
private val sysElevationDelegate = lazy {
    appCtx.resources
        .getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
        .toInt()
}

val AppConst.sysElevation: Int
    get() = sysElevationDelegate.value

private val androidIdDelegate = lazy {
    Settings.System.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: "null"
}

val AppConst.androidId: String
    get() = androidIdDelegate.value

private val appInfoDelegate = lazy {
    val appInfo = AppConst.AppInfo()
    @Suppress("DEPRECATION")
    appCtx.packageManager.getPackageInfo(appCtx.packageName, PackageManager.GET_ACTIVITIES)
        ?.let {
            appInfo.versionName = it.versionName!!
            appInfo.appVariant = when {
                it.packageName.contains("releaseA") -> AppVariant.BETA_RELEASEA
                isBeta -> AppVariant.BETA_RELEASE
                isOfficial -> AppVariant.OFFICIAL
                else -> AppVariant.UNKNOWN
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                appInfo.versionCode = it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                appInfo.versionCode = it.versionCode.toLong()
            }
        }
    appInfo
}

val AppConst.appInfo: AppConst.AppInfo
    get() = appInfoDelegate.value

val AppConst.authority: String
    get() = BuildConfig.APPLICATION_ID + ".fileProvider"
