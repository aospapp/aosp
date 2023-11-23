package com.android.healthconnect.controller.tests.utils.di

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.android.healthconnect.controller.utils.DeviceInfoUtils

class FakeDeviceInfoUtils : DeviceInfoUtils {
    private var sendFeedbackAvailable = false

    private var playStoreAvailable = false

    fun setSendFeedbackAvailability(available: Boolean) {
        sendFeedbackAvailable = available
    }

    fun setPlayStoreAvailability(available: Boolean) {
        playStoreAvailable = available
    }

    override fun isSendFeedbackAvailable(context: Context): Boolean {
        return sendFeedbackAvailable
    }

    override fun getFeedbackReporterPackage(context: Context): String? {
        return null
    }

    override fun isPlayStoreAvailable(context: Context): Boolean {
        return playStoreAvailable
    }

    override fun openHCGetStartedLink(activity: FragmentActivity) {}

    override fun openSendFeedbackActivity(activity: FragmentActivity) {}
}
