package io.github.yozha.movieartdetails.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.FileProvider
import io.github.yozha.movieartdetails.model.CompositeStore
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/**
 * File delivery and broadcasts to the host.
 * FileProvider `${applicationId}.files` exposes only files/published/; read permission
 * is granted to Projectivy on every reply, including when the same URI is returned again.
 */
@Suppress("WrongConstant")
class ProjectivyHost(context: Context, private val store: CompositeStore) : HostBridge {

    private val app = context.applicationContext

    /** Provider UUID as a string from the service meta-data (the official sample wrongly uses an Int resource). */
    private val providerId: String? by lazy { providerId(app) }

    override fun wallpaper(composite: Composite): Wallpaper {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", composite.file)
        app.grantUriPermission(PROJECTIVY_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.markServed(composite.file)
        // actionUri is always null; otherwise Projectivy shows an action icon.
        return Wallpaper(
            uri = uri.toString(),
            type = WallpaperType.IMAGE,
            displayMode = WallpaperDisplayMode.CROP,
            title = composite.title,
            source = composite.sourceUrl,
            author = "TMDB",
            actionUri = null,
        )
    }

    override fun canNotify(): Boolean = app.getSystemService(PowerManager::class.java)?.isInteractive == true

    override fun notifyUpdated() = send(app, WallpaperProviderContract.UpdateReason.DATA_CHANGED, providerId)

    companion object {
        const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"

        fun providerId(context: Context): String? = try {
            context.packageManager
                .getServiceInfo(ComponentName(context, WallpaperProviderService::class.java), PackageManager.GET_META_DATA)
                .metaData?.getString("uuid")
        } catch (_: Exception) {
            null
        }

        /**
         * Broadcast WALLPAPER_PROVIDER_UPDATED. Settings may send it with PREFS_CHANGED after an appearance
         * change: the host responds with TimeElapsed, and the plugin returns the background for the current state.
         */
        fun send(context: Context, reason: Int, id: String? = providerId(context)) {
            if (id.isNullOrEmpty()) return
            val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED)
                .setPackage(PROJECTIVY_PACKAGE)
                .putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, id)
                .putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, reason)
            context.sendBroadcast(intent)
        }
    }
}
