@file:Suppress("unused")

package io.legado.app.utils

import android.icu.text.Collator
import android.icu.util.ULocale
import android.net.Uri
import android.text.Editable
import java.io.File
import java.util.Locale

fun String.toEditable(): Editable = Editable.Factory.getInstance().newEditable(this)

fun String.parseToUri(): Uri {
    return if (isUri()) Uri.parse(this) else {
        Uri.fromFile(File(this))
    }
}

fun String.cnCompare(other: String): Int {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        Collator.getInstance(ULocale.SIMPLIFIED_CHINESE).compare(this, other)
    } else {
        java.text.Collator.getInstance(Locale.CHINA).compare(this, other)
    }
}
