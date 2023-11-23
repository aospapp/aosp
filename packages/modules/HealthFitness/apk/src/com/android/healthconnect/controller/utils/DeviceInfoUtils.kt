package com.android.healthconnect.controller.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.shared.HelpAndFeedbackFragment.Companion.FEEDBACK_INTENT_RESULT_CODE
import com.android.healthconnect.controller.permissions.shared.HelpAndFeedbackFragment.Companion.USER_INITIATED_FEEDBACK_BUCKET_ID
import com.android.settingslib.HelpUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

interface DeviceInfoUtils {
    fun isSendFeedbackAvailable(context: Context): Boolean

    fun getFeedbackReporterPackage(context: Context): String?

    fun isPlayStoreAvailable(context: Context): Boolean

    fun openHCGetStartedLink(activity: FragmentActivity)

    fun openSendFeedbackActivity(activity: FragmentActivity)
}

class DeviceInfoUtilsImpl @Inject constructor() : DeviceInfoUtils {

    companion object {
        private val TAG = "DeviceInfoUtils"
        private val FEEDBACK_REPORTER = "com.google.android.gms"
    }

    override fun isSendFeedbackAvailable(context: Context): Boolean {
        return !TextUtils.isEmpty(getFeedbackReporterPackage(context))
    }

    override fun getFeedbackReporterPackage(context: Context): String? {
        // Check to ensure the feedback reporter is on system image, and feedback reporter is
        // configured to listen to the intent. Otherwise, don't show the "send feedback" preference.
        val intent = Intent(Intent.ACTION_BUG_REPORT)
        val pm = context.packageManager
        val resolvedPackages = pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
        for (info in resolvedPackages) {
            if (info.activityInfo != null) {
                if (!TextUtils.isEmpty(info.activityInfo.packageName)) {
                    try {
                        val ai = pm.getApplicationInfo(info.activityInfo.packageName, 0)
                        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                            // Package is on the system image
                            if (TextUtils.equals(
                                info.activityInfo.packageName, FEEDBACK_REPORTER)) {
                                return FEEDBACK_REPORTER
                            }
                        }
                    } catch (e: PackageManager.NameNotFoundException) {
                        // No need to do anything here.
                    }
                }
            }
        }
        return null
    }

    override fun isPlayStoreAvailable(context: Context): Boolean {
        val playStorePackageName = context.resources?.getString(R.string.playstore_package_name)
        if (TextUtils.isEmpty(playStorePackageName) || playStorePackageName == null) {
            // Package name not configured. Return.
            return false
        }
        val pm = context.packageManager
        return try {
            pm?.getApplicationInfo(playStorePackageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun openHCGetStartedLink(activity: FragmentActivity) {
        val helpUrlString = activity.getString(R.string.hc_get_started_link)
        val fullUri = HelpUtils.uriWithAddedParameters(activity, Uri.parse(helpUrlString))
        val intent =
            Intent(Intent.ACTION_VIEW, fullUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Unable to open help center URL.", e)
        }
    }

    override fun openSendFeedbackActivity(activity: FragmentActivity) {
        val intent = Intent(Intent.ACTION_BUG_REPORT)
        intent.putExtra("category_tag", USER_INITIATED_FEEDBACK_BUCKET_ID)
        activity.startActivityForResult(intent, FEEDBACK_INTENT_RESULT_CODE)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceInfoUtilsEntryPoint {
    fun deviceInfoUtils(): DeviceInfoUtils
}

@Module
@InstallIn(SingletonComponent::class)
class DeviceInfoUtilsModule {
    @Provides
    fun providesDeviceInfoUtils(): DeviceInfoUtils {
        return DeviceInfoUtilsImpl()
    }
}
