package io.github.yozha.movieartdetails.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.ServiceLocator
import io.github.yozha.movieartdetails.model.AppBadge
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.render.ImageDecoder
import java.io.File
import java.util.concurrent.Future

/**
 * Appearance preview: a finished 1280×720 frame with the current settings and a sample title.
 * Rendered by the same WallpaperRenderer as the service; the background is a synthetic gradient
 * with a bright spot on the left so the scrim under the text is visible, plus placeholder cards where the
 * launcher's card row is set to start. The plugin's own banner stands in for the source app's badge.
 * No network needed. BACK returns to settings.
 */
class PreviewFragment : Fragment() {
    private var task: Future<*>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_preview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.requestFocus()
        val image = view.findViewById<ImageView>(R.id.preview_image)
        val status = view.findViewById<TextView>(R.id.preview_status)
        val ctx = requireContext().applicationContext
        val info = sampleInfo()
        task = SettingsTasks.run(this, {
            val appearance = AppSettings(ctx).appearance()
            val out = File(ctx.cacheDir, PREVIEW_FILE)
            val backdrop = syntheticBackdrop(appearance.rowPositionPct)
            val badge = if (appearance.showAppBadge) sampleBadge(ctx) else null
            try {
                ServiceLocator.renderer.render(backdrop, null, info, appearance, out, badge)
            } finally {
                backdrop.recycle()
                badge?.bitmap?.recycle()
            }
            val full = ImageDecoder.decodeBackdrop(out, PREVIEW_W, PREVIEW_H).also { out.delete() }
            // The frame is rendered at 1920×1080, and 1080/720 is not a power of two, so inSampleSize cannot reduce it; keep 1280×720.
            if (full == null || (full.width == PREVIEW_W && full.height == PREVIEW_H)) {
                full
            } else {
                Bitmap.createScaledBitmap(full, PREVIEW_W, PREVIEW_H, true).also { if (it !== full) full.recycle() }
            }
        }) { r ->
            val bitmap = r.getOrNull()
            // The view may have changed (or gone) while the fragment stays attached: do not use the old view.
            if (this.view !== view) {
                bitmap?.recycle()
            } else if (bitmap != null) {
                image.setImageBitmap(bitmap)
                status.visibility = View.GONE
            } else {
                status.setText(R.string.preview_failed)
            }
        }
    }

    override fun onDestroyView() {
        task?.cancel(true)
        task = null
        super.onDestroyView()
    }

    private fun sampleInfo() = DisplayInfo(
        mediaType = MediaType.MOVIE,
        tmdbId = 0,
        language = AppSettings(requireContext()).language(),
        title = getString(R.string.preview_sample_title),
        yearText = "2024",
        typeText = getString(R.string.preview_sample_type),
        durationText = getString(R.string.preview_sample_duration),
        genres = getString(R.string.preview_sample_genres).split('|'),
        ratingText = getString(R.string.preview_sample_rating),
        overview = getString(R.string.preview_sample_overview),
        logoPath = null,
        backdropPath = null,
        fingerprint = "preview",
    )

    /**
     * 1920×1080: a dark blue gradient with a bright spot under the left block, the worst case for contrast, and
     * placeholders for the card row starting at [rowPct]: a header bar and a row of cards, as in Projectivy.
     */
    private fun syntheticBackdrop(rowPct: Float): Bitmap {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 1920f, 1080f, 0xFF2B4C7E.toInt(), 0xFF0E1A2B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1920f, 1080f, paint)
        paint.shader = RadialGradient(520f, 300f, 520f, 0xCCF2E3C6.toInt(), 0x00F2E3C6, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1920f, 1080f, paint)

        paint.shader = null
        paint.color = ROW_PLACEHOLDER
        val row = (if (rowPct.isFinite()) rowPct else 0.55f) * 1080f
        canvas.drawRoundRect(RectF(115f, row + 10f, 420f, row + 45f), 8f, 8f, paint)
        var left = 75f
        while (left < 1920f) {
            canvas.drawRoundRect(RectF(left, row + 75f, left + 255f, row + 245f), 16f, 16f, paint)
            left += 273f
        }
        return bmp
    }

    /** The plugin's banner as a sample source-app badge; null if it cannot be drawn. */
    private fun sampleBadge(ctx: Context): AppBadge? {
        val banner = ContextCompat.getDrawable(ctx, R.drawable.ic_banner) ?: return null
        val bmp = Bitmap.createBitmap(SAMPLE_BADGE_W, SAMPLE_BADGE_H, Bitmap.Config.ARGB_8888)
        banner.setBounds(0, 0, SAMPLE_BADGE_W, SAMPLE_BADGE_H)
        banner.draw(Canvas(bmp))
        return AppBadge(bmp, banner = true)
    }

    companion object {
        private const val SAMPLE_BADGE_W = 224
        private const val SAMPLE_BADGE_H = 126
        private const val PREVIEW_FILE = "settings_preview.jpg"
        private const val PREVIEW_W = 1280
        private const val PREVIEW_H = 720
        private const val ROW_PLACEHOLDER = 0x40FFFFFF

        fun show(fm: FragmentManager) {
            fm.beginTransaction()
                .replace(android.R.id.content, PreviewFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}
