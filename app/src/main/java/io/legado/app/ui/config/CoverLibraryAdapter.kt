package io.legado.app.ui.config

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCoverLibraryBinding
import io.legado.app.help.glide.ImageLoader

/**
 * 默认封面图库条目：缩略图 + 右上角移除角标。
 */
class CoverLibraryAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<String, ItemCoverLibraryBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<String>
        get() = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }

    override fun getViewBinding(parent: ViewGroup): ItemCoverLibraryBinding =
        ItemCoverLibraryBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCoverLibraryBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        ImageLoader.load(context, item).centerCrop().into(binding.ivCover)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCoverLibraryBinding) {
        binding.ivRemove.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.remove(it) }
        }
    }

    interface CallBack {
        fun remove(path: String)
    }

}
