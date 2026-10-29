package io.github.yozha.movieartdetails.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import io.github.yozha.movieartdetails.model.AppBadge
import kotlin.math.roundToInt

/** Source-app badges: which app a card comes from ([SourceApps]) and its decoded banner or icon. */
interface AppBadges {
    /** Binder-thread safe, no decoding. */
    fun forIconUri(iconUri: String?): BadgeRef?
    /** Binder-thread safe, no decoding. */
    fun forPackage(packageName: String?): BadgeRef?
    /** The banner (or icon) of [ref], cached per package and version; null when there is none. Pool threads only. */
    fun load(ref: BadgeRef): AppBadge?
    /** Prepares lookups ahead of the first card; called on a background thread. */
    fun warmUp() {}

    companion object {
        /** No badges at all. */
        val NONE: AppBadges = object : AppBadges {
            override fun forIconUri(iconUri: String?): BadgeRef? = null
            override fun forPackage(packageName: String?): BadgeRef? = null
            override fun load(ref: BadgeRef): AppBadge? = null
        }
    }
}

/** An app the user can pick as the source of a host's cards. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * [AppCatalog] on PackageManager. Without QUERY_ALL_PACKAGES only apps with a launcher activity are visible
 * (the manifest's `<queries>`), which covers every app that can put cards on the home screen.
 */
class AndroidAppCatalog(context: Context) : AppCatalog {
    private val app = context.applicationContext
    private val pm: PackageManager get() = app.packageManager

    override fun providerPackage(authority: String): String? = pm.resolveContentProvider(authority, 0)?.packageName

    override fun launchablePackages(): Collection<String> =
        LAUNCHER_CATEGORIES.flatMapTo(LinkedHashSet()) { category -> launchers(category).map { it.activityInfo.packageName } }

    override fun version(packageName: String): Long? = try {
        pm.getPackageInfo(packageName, 0).lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** TV apps first, then phone apps, each group sorted by label; without this plugin. */
    fun installedApps(): List<InstalledApp> = tvFirst(
        LAUNCHER_CATEGORIES.map { category ->
            launchers(category).map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString().ifBlank { it.activityInfo.packageName }) }
        },
        exclude = app.packageName,
    )

    fun label(packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun launchers(category: String) =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    internal companion object {
        private val LAUNCHER_CATEGORIES = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

        /**
         * [groups] in order (TV launcher apps, then the rest), each sorted by label; an app listed in an earlier
         * group or more than once stays only in its first place, and [exclude] is left out.
         */
        fun tvFirst(groups: List<List<InstalledApp>>, exclude: String): List<InstalledApp> {
            val seen = hashSetOf(exclude)
            return groups.flatMap { group -> group.filter { seen.add(it.packageName) }.sortedBy { it.label.lowercase() } }
        }
    }
}

/**
 * Badges on PackageManager: the TV banner of the app's leanback activity or of the application, otherwise its
 * icon, drawn into a bitmap no taller than the block needs. Decoded bitmaps are kept for the last [CACHE_SIZE] apps,
 * keyed by package and version, and never recycled (renderers only draw them).
 */
class AndroidAppBadges(context: Context, private val apps: SourceApps) : AppBadges {
    private val app = context.applicationContext
    private val cache = LruCache<String, AppBadge>(CACHE_SIZE)

    override fun forIconUri(iconUri: String?): BadgeRef? = apps.forIconUri(iconUri)

    override fun forPackage(packageName: String?): BadgeRef? = apps.forPackage(packageName)

    override fun load(ref: BadgeRef): AppBadge? {
        cache.get(ref.key)?.let { return it }
        val badge = try {
            draw(ref.packageName)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        cache.put(ref.key, badge)
        return badge
    }

    override fun warmUp() = apps.warmUp()

    private fun draw(pkg: String): AppBadge? {
        val pm = app.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg)
        val banner = launch?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
            ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
        if (banner != null) {
            val w = banner.intrinsicWidth
            val h = banner.intrinsicHeight
            // A banner is 16:9 (320×180 dp) by convention; a drawable without a size gets that shape.
            val aspect = if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f
            return AppBadge(toBitmap(banner, (BANNER_H * aspect).roundToInt().coerceIn(1, BANNER_H * 4), BANNER_H), banner = true)
        }
        val icon = launch?.let { runCatching { pm.getActivityIcon(it) }.getOrNull() } ?: pm.getApplicationIcon(pkg)
        return AppBadge(toBitmap(icon, ICON_SIZE, ICON_SIZE), banner = false)
    }

    private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val CACHE_SIZE = 8
        /** Twice the tallest badge plate (LayoutEngine), so it is only ever scaled down. */
        const val BANNER_H = 128
        const val ICON_SIZE = 128
    }
}
