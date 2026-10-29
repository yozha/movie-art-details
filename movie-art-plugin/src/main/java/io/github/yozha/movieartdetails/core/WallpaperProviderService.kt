package io.github.yozha.movieartdetails.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/**
 * Projectivy wallpaper provider. All logic lives in [Coordinator]; this is just the binder.
 * No method lets an exception escape, OutOfMemoryError included.
 */
@Suppress("WrongConstant")
class WallpaperProviderService : Service() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        runCatching { ServiceLocator.maintainAsync() }
    }

    override fun onBind(intent: Intent): IBinder {
        // Projectivy binds before the first card: connect to TMDB now rather than on the first cold card.
        runCatching { ServiceLocator.warmUpAsync() }
        return binder
    }

    /**
     * The service is exported (Projectivy requires it), so only calls from Projectivy itself and from
     * our own process are accepted; otherwise any app could burn the user's TMDB key
     * and change their wallpaper and settings.
     */
    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return runCatching {
            packageManager.getPackagesForUid(uid)?.contains(ProjectivyHost.PROJECTIVY_PACKAGE) == true
        }.getOrDefault(false)
    }

    private val binder = object : IWallpaperProviderService.Stub() {

        override fun getWallpapers(event: Event?): List<Wallpaper> = try {
            if (!callerAllowed()) emptyList() else ServiceLocator.coordinator.getWallpapers(event)
        } catch (t: Throwable) {
            // Even if ServiceLocator failed to initialize: an empty list here would leave the previous movie's background up.
            if (event == null) emptyList() else listOf(Wallpaper(Coordinator.DEFAULT_NEUTRAL, WallpaperType.COLOR))
        }

        /** Allowlisted settings only, with schemaVersion, without the key or history. */
        override fun getPreferences(): String = try {
            if (!callerAllowed()) "{}" else ServiceLocator.settings.exportJson()
        } catch (t: Throwable) {
            "{}"
        }

        /** No clear(); AppSettings rejects an incompatible schema without crashing. */
        override fun setPreferences(params: String?) {
            try {
                if (callerAllowed() && !params.isNullOrBlank()) ServiceLocator.settings.importJson(params)
            } catch (_: Throwable) {
            }
        }
    }
}
