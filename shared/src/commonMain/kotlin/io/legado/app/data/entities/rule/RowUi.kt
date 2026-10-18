package io.legado.app.data.entities.rule

data class RowUi(
    var name: String = "",
    var type: String = "text",
    var action: String? = null,
    var style: FlexChildStyle? = null,
    // 登录UI v2 扩展,均可空,v1 GSON 解析零影响
    var key: String? = null,
    var hint: String? = null,
    var value: String? = null,
    var options: List<String>? = null,
    var countdown: Int? = null,
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val label = "label"
        const val select = "select"
        const val toggle = "toggle"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

}
