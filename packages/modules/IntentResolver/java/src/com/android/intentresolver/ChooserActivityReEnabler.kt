package com.android.intentresolver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Ensures that the unbundled version of [ChooserActivity] does not get stuck in a disabled state.
 */
class ChooserActivityReEnabler : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.packageManager.setComponentEnabledSetting(
                CHOOSER_COMPONENT,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                /* flags = */ 0,
            )

            // This only needs to be run once, so we disable ourself to avoid additional startup
            // process on future boots
            context.packageManager.setComponentEnabledSetting(
                SELF_COMPONENT,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                /* flags = */ 0,
            )
        }
    }

    companion object {
        private const val CHOOSER_PACKAGE = "com.android.intentresolver"
        private val CHOOSER_COMPONENT =
            ComponentName(CHOOSER_PACKAGE, "$CHOOSER_PACKAGE.ChooserActivity")
        private val SELF_COMPONENT =
            ComponentName(CHOOSER_PACKAGE, "$CHOOSER_PACKAGE.ChooserActivityReEnabler")
    }
}
