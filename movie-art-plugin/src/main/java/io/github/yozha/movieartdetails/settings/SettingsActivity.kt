package io.github.yozha.movieartdetails.settings

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import io.github.yozha.movieartdetails.core.ServiceLocator

/**
 * Settings screen: fully operable with the remote; screens are GuidedStepSupportFragment.
 * BACK on the root screen calls finish(): on Android 14, moving the root LAUNCHER activity to the background
 * was observed to crash system_server.
 */
class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        if (savedInstanceState == null) GuidedStepSupportFragment.addAsRoot(this, RootStep(), android.R.id.content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                val top = fm.findFragmentById(android.R.id.content) as? GuidedStepSupportFragment
                when {
                    top != null && top.isExpanded -> top.collapseAction(true)
                    fm.backStackEntryCount > 0 -> fm.popBackStack()
                    else -> finish()
                }
            }
        })
    }
}
