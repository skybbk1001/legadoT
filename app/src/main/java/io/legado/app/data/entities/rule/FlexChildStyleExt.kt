package io.legado.app.data.entities.rule

import android.view.View
import com.google.android.flexbox.FlexboxLayout

fun FlexChildStyle.apply(view: View) {
    val lp = view.layoutParams as FlexboxLayout.LayoutParams
    lp.flexGrow = layout_flexGrow
    lp.flexShrink = layout_flexShrink
    lp.alignSelf = alignSelf()
    lp.flexBasisPercent = layout_flexBasisPercent
    lp.isWrapBefore = layout_wrapBefore
}
