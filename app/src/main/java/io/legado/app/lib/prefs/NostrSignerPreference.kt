package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.nostr.NostrProfileStore
import io.legado.app.help.nostr.NostrSigners
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

/**
 * 签名器状态行：头像 + 昵称 + 可点击复制的公钥缩写 + 「连接 Nostr / 退出」。
 *
 * 直接继承 [androidx.preference.Preference]（不走 [io.legado.app.lib.prefs.Preference]：
 * 后者的 bindView 硬绑 4 个固定 id，这里的自绘布局用不上），账号与资料在
 * [onBindViewHolder] 时现读 [NostrSigners]/[NostrProfileStore]，行内动作由宿主通过
 * [onConnectClick]/[onLogoutClick] 注入，连接、退出、资料同步后由宿主调 [refresh] 重绘。
 */
class NostrSignerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : androidx.preference.Preference(context, attrs) {

    /** 打开签名器弹窗（未登录时右侧动作也走这里）。 */
    var onConnectClick: (() -> Unit)? = null

    /** 退出当前使用的签名器。 */
    var onLogoutClick: (() -> Unit)? = null

    init {
        layoutResource = R.layout.view_nostr_signer_preference
        // 行内自己处理点击（含右侧动作），不走框架的 Preference 点击分发。
        isSelectable = false
    }

    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val avatar = holder.findViewById(R.id.iv_avatar) as? ImageView ?: return
        val name = holder.findViewById(R.id.tv_signer_name) as? TextView ?: return
        val pubkeyView = holder.findViewById(R.id.tv_signer_pubkey) as? TextView ?: return
        val action = holder.findViewById(R.id.tv_signer_action) as? TextView ?: return

        val pubkey = NostrSigners.currentPubkey()?.takeIf { it.isNotBlank() }
        val profile = pubkey?.let { NostrProfileStore.get(it) }
        val picture = profile?.picture?.takeIf { it.isNotBlank() }
        if (picture == null) {
            // 无资料时留空占位（而非 GONE），保持行高与未登录态一致。
            avatar.setImageDrawable(null)
            avatar.visibility = View.INVISIBLE
        } else {
            avatar.visibility = View.VISIBLE
            ImageLoader.load(context, picture).circleCrop().into(avatar)
        }

        if (pubkey == null) {
            name.text = ""
            pubkeyView.text = ""
            pubkeyView.setOnClickListener(null)
            action.setText(R.string.nostr_signer_connect_nostr)
        } else {
            name.text = profile?.bestName ?: pubkey.take(12)
            pubkeyView.text = abbreviate(pubkey)
            pubkeyView.setOnClickListener {
                context.sendToClip(pubkey)
                context.toastOnUi(R.string.nostr_signer_pubkey_copied)
            }
            action.setText(R.string.nostr_signer_logout)
        }

        holder.itemView.setOnClickListener { onConnectClick?.invoke() }
        action.setOnClickListener {
            if (pubkey == null) onConnectClick?.invoke() else onLogoutClick?.invoke()
        }
    }

    private fun abbreviate(pubkey: String): String =
        if (pubkey.length > 16) "${pubkey.take(8)}…${pubkey.takeLast(6)}" else pubkey
}
