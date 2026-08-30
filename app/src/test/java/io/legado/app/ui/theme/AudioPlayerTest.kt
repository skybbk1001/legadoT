package io.legado.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** N3b 音频播放页门面哨兵 */
class AudioPlayerTest {

    @Test
    fun `ambient background is a shared extension`() {
        val ext = File("src/main/java/io/legado/app/utils/AmbientBackground.kt").readText()
        assertTrue(ext.contains("fun View.applyAmbientBackground"))
        assertTrue("染不是涂:必须 blend", ext.contains("blendARGB"))
        assertTrue("eink 守卫", ext.contains("isEInkMode"))
        assertTrue("默认封面守卫", ext.contains("isDefaultCover"))
        // IMMERSIVE 的有机纹理底:微缩放大伪模糊(封面缩 16×16 铺满,插值即模糊)——
        // 治"几何色块单调",用户验收回合定案,不许退回纯几何渐变
        assertTrue("IMMERSIVE 必须有微缩纹理底", ext.contains("createScaledBitmap"))
        assertTrue("纹理上必须有派生色蒙层(对比度兜底)", ext.contains("setAlphaComponent"))
        // N3a 改调共享扩展,不再自持 applyAmbientHeader 全身逻辑
        val info = File("src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt").readText()
        assertTrue("N3a 复用共享扩展", info.contains("applyAmbientBackground"))
    }

    @Test
    fun `audio layout is modernized and white-hardcode retired`() {
        val xml = File("src/main/res/layout/activity_audio_play.xml").readText()
        assertTrue("蒙层退役", !xml.contains("#50000000"))
        assertTrue("圆形封面退役", !xml.contains("CircleImageView"))
        assertTrue("大圆角方卡", xml.contains("MaterialCardView") && xml.contains("iv_cover"))
        assertTrue("进度 Slider 化", xml.contains("slider.Slider") && !xml.contains("<SeekBar"))
        assertTrue("写死白/黑退役", !xml.contains("md_white_1000") && !xml.contains("md_black_1000"))
        assertTrue("加载 CircularProgressIndicator", xml.contains("progressindicator.CircularProgressIndicator") && !xml.contains("<ProgressBar"))
        val act = File("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt").readText()
        assertTrue("接氛围管线", act.contains("applyAmbientBackground"))
        assertTrue("播放键 morph", act.contains("ShapeMorph"))
        assertTrue("Theme.Dark 退役", !act.contains("Theme.Dark"))
    }

    @Test
    fun `audio legacy assets retired`() {
        // N3b Task3 退役清理：Task 2 后确认零引用的两资产删除
        // 圆形 transport 按钮背景 selector（Task 2 全改 borderless）
        assertTrue("圆钮背景退役", !File("src/main/res/drawable/selector_circle_btn_bg.xml").exists())
        // 模糊封面加载器（Task 2 改氛围渐变，其独占依赖 BlurTransformation 连带退役）
        assertTrue("loadBlur 退役", !File("src/main/java/io/legado/app/model/BookCover.kt").readText().contains("fun loadBlur"))
        assertTrue("BlurTransformation 连带退役", !File("src/main/java/io/legado/app/help/glide/BlurTransformation.kt").exists())
        // 保守边界：ShapeAppearanceLegadoAudioPlayFab 仍被填充播放键 shapeAppearanceOverlay 消费（Task 2 保留），故不退役
        assertTrue("圆钮形状 overlay 仍在用故保留", File("src/main/res/values/styles.xml").readText().contains("ShapeAppearanceLegadoAudioPlayFab"))
    }
}
