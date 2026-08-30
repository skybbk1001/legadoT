package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding


class ReadAloudDialog : BaseDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack,
    SleepTimerDialog.CallBack {

    /** 贴底面板自设背景(bottomBackground),豁免统一圆角模板 */
    override val dialogForm = DialogForm.SELF_MANAGED

    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
            val radius = requireContext().resources.getDimension(R.dimen.radius_l)
            rootView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setColor(bg)
            }
            tvPre.setTextColor(textColor)
            tvNext.setTextColor(textColor)
            ivPlayPrev.setColorFilter(textColor)
            ivPlayPause.setColorFilter(textColor)
            ivPlayNext.setColorFilter(textColor)
            ivStop.setColorFilter(textColor)
            ivTimer.setColorFilter(textColor)
            tvTimer.setTextColor(textColor)
            ivTtsSpeechReduce.setColorFilter(textColor)
            tvTtsSpeed.setTextColor(textColor)
            tvTtsSpeedValue.setTextColor(textColor)
            ivTtsSpeechAdd.setColorFilter(textColor)
            arrayOf(seekTimer, seekTtsSpeechRate).forEach { slider ->
                slider.applyAppTint(textColor)
            }
            llCatalog.setTint(textColor)
            llMainMenu.setTint(textColor)
            llToBackstage.setTint(textColor)
            llSetting.setTint(textColor)
            cbTtsFollowSys.setTextColor(textColor)
            ivEngine.setColorFilter(textColor)
            tvEngineName.setTextColor(textColor)
            ivEngineArrow.setColorFilter(textColor)
        }
        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        upPlayState()
        upEngineName()
        upStopText()
        cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        upTtsSpeechRateEnabled(!cbTtsFollowSys.isChecked)
        upSeekTimer()
    }

    private fun initEvent() = binding.run {
        llMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        llSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        llEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        tvPre.setOnClickListener { ReadAloud.prevChapter(requireContext()) }
        tvNext.setOnClickListener { ReadAloud.nextChapter(requireContext()) }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        llCatalog.setOnClickListener { callBack?.openChapterList() }
        llToBackstage.setOnClickListener { callBack?.finish() }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        ivTtsSpeechReduce.setOnClickListener {
            seekTtsSpeechRate.value = (AppConfig.ttsSpeechRate - 1).toFloat()
                .coerceIn(seekTtsSpeechRate.valueFrom, seekTtsSpeechRate.valueTo)
            AppConfig.ttsSpeechRate -= 1
            upTtsSpeechRate()
        }
        ivTtsSpeechAdd.setOnClickListener {
            seekTtsSpeechRate.value = (AppConfig.ttsSpeechRate + 1).toFloat()
                .coerceIn(seekTtsSpeechRate.valueFrom, seekTtsSpeechRate.valueTo)
            AppConfig.ttsSpeechRate += 1
            upTtsSpeechRate()
        }
        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.value.toInt()
            toastOnUi("保存设定时间成功！")
        }
        tvTimer.setOnClickListener {
            showDialogFragment(SleepTimerDialog())
        }
        //设置保存的默认值
        seekTtsSpeechRate.value = AppConfig.ttsSpeechRate.toFloat()
            .coerceIn(seekTtsSpeechRate.valueFrom, seekTtsSpeechRate.valueTo)
        seekTtsSpeechRate.addOnChangeListener { _, value, _ ->
            upTtsSpeechRateText(value.toInt())
        }
        seekTtsSpeechRate.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                AppConfig.ttsSpeechRate = slider.value.toInt()
                upTtsSpeechRate()
            }
        })
        seekTimer.addOnChangeListener { _, value, fromUser ->
            if (fromUser) upTimerText(value.toInt())
        }
        seekTimer.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                ReadAloud.setTimer(requireContext(), slider.value.toInt())
            }
        })
    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(AppConfig.ttsSpeechRate)
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.setImageResource(R.drawable.ic_pause_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.setImageResource(R.drawable.ic_play_24dp)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.ivPlayPause.setColorFilter(textColor)
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            val progress = when {
                BaseReadAloudService.timeMinute > 0 -> BaseReadAloudService.timeMinute
                BaseReadAloudService.chapterToStop > 0 -> 0
                else -> AppConfig.ttsTimer
            }
            binding.seekTimer.value = progress.toFloat()
                .coerceIn(binding.seekTimer.valueFrom, binding.seekTimer.valueTo)
        }
    }

    /** 显示当前停止设置:章数优先, 其次时间, 都无则显示"定时" */
    private fun upStopText() {
        val chapter = BaseReadAloudService.chapterToStop
        val minute = BaseReadAloudService.timeMinute
        binding.tvTimer.text = when {
            chapter > 0 -> getString(R.string.read_aloud_stop_chapters, chapter)
            minute > 0 -> getString(R.string.timer_m, minute)
            else -> getString(R.string.set_timer)
        }
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.tvTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = ((value + 5) / 10f).toString()
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun upEngineName() {
        binding.tvEngineName.text = ReadAloud.getEngineName(requireContext())
    }

    override fun upSpeakEngineSummary() {
        upEngineName()
    }

    override fun onSleepTimerMinute(minute: Int) {
        ReadAloud.setTimer(requireContext(), minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        ReadAloud.setChapterStop(requireContext(), count)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            binding.seekTimer.value = it.toFloat()
                .coerceIn(binding.seekTimer.valueFrom, binding.seekTimer.valueTo)
            upStopText()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER) { upStopText() }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun backToSpeakingPosition()
        fun finish()
    }
}