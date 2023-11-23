package com.android.healthconnect.controller.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log

/** Functions that help dealing with app stores. */
private const val TAG = "HCAppStoreUtil"

private fun resolveIntent(context: Context, intent: Intent): Intent? {
    val resolveInfoResult = context.packageManager.resolveActivity(intent, 0)
    return if (resolveInfoResult != null) {
        Intent(intent.action)
            .setClassName(
                resolveInfoResult.activityInfo.packageName, resolveInfoResult.activityInfo.name)
    } else null
}

private fun getInstallerPackageName(context: Context, packageName: String): String? {
    var installerPackageName: String? = null

    try {
        val source = context.packageManager.getInstallSourceInfo(packageName)

        // By default use the installing package name
        installerPackageName = source.installingPackageName

        // Use the recorded originating package name only if the initiating package is a system
        // app (eg. Package Installer). The originating package is not verified by the platform,
        // so we choose to ignore this when supplied by a non-system app.
        val originatingPackageName = source.originatingPackageName
        val initiatingPackageName = source.initiatingPackageName
        if (originatingPackageName != null && initiatingPackageName != null) {
            val ai = context.packageManager.getApplicationInfo(initiatingPackageName, 0)
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                installerPackageName = originatingPackageName
            }
        }
    } catch (exception: NameNotFoundException) {
        Log.e(TAG, "Exception while retrieving the package installer of $packageName", exception)
    }

    return installerPackageName
}

private fun getAppStoreLink(
    context: Context,
    installerPackageName: String?,
    packageName: String
): Intent? {
    val intent = Intent(Intent.ACTION_SHOW_APP_INFO)
    if (installerPackageName != null) {
        // if we cannot find the installer package name we can still
        // send the intent which should be handled by one app
        intent.setPackage(installerPackageName)
    }

    val result = resolveIntent(context, intent)
    if (result != null) {
        result.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
        return result
    }

    return null
}

fun getAppStoreLink(context: Context, packageName: String): Intent? {
    val installerPackageName = getInstallerPackageName(context, packageName)
    return getAppStoreLink(context, installerPackageName, packageName)
}
