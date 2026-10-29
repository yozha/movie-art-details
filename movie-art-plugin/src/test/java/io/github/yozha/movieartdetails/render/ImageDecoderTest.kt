package io.github.yozha.movieartdetails.render

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.TextPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecoderTest {

    @Test
    fun wideLogoDecodedAtBlockWidth() {
        // The default bounds: 520×130, or 883 px wide for an aspect ratio above 4.
        fun decodedWidth(w: Int, h: Int) = w / ImageDecoder.sampleSize(ImageDecoder.logoScale(w, h, 520, 130, 883))
        // 2100×175 (aspect 12): 1050 px wide, no longer 525 px (inSampleSize 4) from the 520 px box.
        assertEquals(1050, decodedWidth(2100, 175))
        // A regular logo still decodes for the 520×130 box.
        assertEquals(1000, decodedWidth(2000, 500))
        assertEquals(500, decodedWidth(2000, 600))
        // Never below what the block needs.
        for ((w, h) in listOf(2100 to 175, 4000 to 300, 1800 to 400, 1000 to 1000)) {
            val dw = decodedWidth(w, h)
            val aspect = w.toFloat() / h
            val boxW = if (aspect > 4f) 883f else 520f
            assertTrue("$w×$h", dw >= minOf(boxW, 130f * aspect, w.toFloat()) - 1f)
        }
    }

    @Test
    fun logoBoundsFollowFrameAndPreset() {
        assertEquals(ImageDecoder.LogoBounds(520, 130, 883), ImageDecoder.logoBounds(AppearanceSettings()))
        assertEquals(ImageDecoder.LogoBounds(480, 140, 480), ImageDecoder.logoBounds(AppearanceSettings(blockWidthPct = 0.25f, textPreset = TextPreset.LARGE)))
        assertEquals(ImageDecoder.LogoBounds(460, 110, 1292), ImageDecoder.logoBounds(AppearanceSettings(blockWidthPct = 0.9f, textPreset = TextPreset.SMALL)))
    }
}
