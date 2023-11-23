/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("DEPRECATION")

package com.android.permissioncontroller.permission.ui.model

import android.Manifest
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.Manifest.permission_group.LOCATION
import android.Manifest.permission_group.READ_MEDIA_VISUAL
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED
import android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED
import android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET
import android.health.connect.HealthConnectManager.ACTION_REQUEST_HEALTH_PERMISSIONS
import android.health.connect.HealthConnectManager.isHealthPermission
import android.health.connect.HealthPermissions.HEALTH_PERMISSION_GROUP
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.permission.PermissionManager
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.modules.utils.build.SdkLevel
import com.android.permission.safetylabel.SafetyLabel
import com.android.permissioncontroller.Constants
import com.android.permissioncontroller.DeviceUtils
import com.android.permissioncontroller.PermissionControllerStatsLog
import com.android.permissioncontroller.PermissionControllerStatsLog.GRANT_PERMISSIONS_ACTIVITY_BUTTON_ACTIONS
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_DENIED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_POLICY_FIXED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_RESTRICTED_PERMISSION
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_USER_FIXED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__PHOTOS_SELECTED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_IN_SETTINGS
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE_IN_SETTINGS
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_IN_SETTINGS
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_ONE_TIME
import com.android.permissioncontroller.PermissionControllerStatsLog.PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_IGNORED
import com.android.permissioncontroller.auto.DrivingDecisionReminderService
import com.android.permissioncontroller.permission.data.LightAppPermGroupLiveData
import com.android.permissioncontroller.permission.data.LightPackageInfoLiveData
import com.android.permissioncontroller.permission.data.PackagePermissionsLiveData
import com.android.permissioncontroller.permission.data.v34.SafetyLabelInfoLiveData
import com.android.permissioncontroller.permission.data.SmartUpdateMediatorLiveData
import com.android.permissioncontroller.permission.data.get
import com.android.permissioncontroller.permission.model.AppPermissionGroup
import com.android.permissioncontroller.permission.model.livedatatypes.LightAppPermGroup
import com.android.permissioncontroller.permission.model.livedatatypes.LightPackageInfo
import com.android.permissioncontroller.permission.model.livedatatypes.LightPermGroupInfo
import com.android.permissioncontroller.permission.service.PermissionChangeStorageImpl
import com.android.permissioncontroller.permission.service.v33.PermissionDecisionStorageImpl
import com.android.permissioncontroller.permission.ui.AutoGrantPermissionsNotifier
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.ALLOW_ALL_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.ALLOW_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.ALLOW_FOREGROUND_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.ALLOW_ONE_TIME_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.ALLOW_SELECTED_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.COARSE_RADIO_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DENY_AND_DONT_ASK_AGAIN_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DENY_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DIALOG_WITH_BOTH_LOCATIONS
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DIALOG_WITH_COARSE_LOCATION_ONLY
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DIALOG_WITH_FINE_LOCATION_ONLY
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.DONT_ALLOW_MORE_SELECTED_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.FINE_RADIO_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.INTENT_PHOTOS_SELECTED
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.LINK_TO_PERMISSION_RATIONALE
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.LINK_TO_SETTINGS
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.LOCATION_ACCURACY_LAYOUT
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NEXT_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NEXT_LOCATION_DIALOG
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NO_UPGRADE_AND_DONT_ASK_AGAIN_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NO_UPGRADE_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NO_UPGRADE_OT_AND_DONT_ASK_AGAIN_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsActivity.NO_UPGRADE_OT_BUTTON
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.CANCELED
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.DENIED
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.DENIED_DO_NOT_ASK_AGAIN
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.DENIED_MORE
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_ALWAYS
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_FOREGROUND_ONLY
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_ONE_TIME
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_USER_SELECTED
import com.android.permissioncontroller.permission.ui.ManagePermissionsActivity
import com.android.permissioncontroller.permission.ui.ManagePermissionsActivity.EXTRA_RESULT_PERMISSION_INTERACTED
import com.android.permissioncontroller.permission.ui.ManagePermissionsActivity.EXTRA_RESULT_PERMISSION_RESULT
import com.android.permissioncontroller.permission.ui.v34.PermissionRationaleActivity
import com.android.permissioncontroller.permission.utils.v31.AdminRestrictedPermissionsUtils
import com.android.permissioncontroller.permission.utils.KotlinUtils
import com.android.permissioncontroller.permission.utils.KotlinUtils.getDefaultPrecision
import com.android.permissioncontroller.permission.utils.KotlinUtils.grantBackgroundRuntimePermissions
import com.android.permissioncontroller.permission.utils.KotlinUtils.grantForegroundRuntimePermissions
import com.android.permissioncontroller.permission.utils.KotlinUtils.isLocationAccuracyEnabled
import com.android.permissioncontroller.permission.utils.KotlinUtils.revokeBackgroundRuntimePermissions
import com.android.permissioncontroller.permission.utils.KotlinUtils.revokeForegroundRuntimePermissions
import com.android.permissioncontroller.permission.utils.PermissionMapping
import com.android.permissioncontroller.permission.utils.PermissionMapping.getPartialStorageGrantPermissionsForGroup
import com.android.permissioncontroller.permission.utils.SafetyNetLogger
import com.android.permissioncontroller.permission.utils.Utils
import com.android.permissioncontroller.permission.utils.v34.SafetyLabelUtils

/**
 * ViewModel for the GrantPermissionsActivity. Tracks all permission groups that are affected by
 * the permissions requested by the user, and generates a RequestInfo object for each group, if
 * action is needed. It will not return any data if one of the requests is malformed.
 *
 * @param app: The current application
 * @param packageName: The packageName permissions are being requested for
 * @param requestedPermissions: The list of permissions requested
 * @param sessionId: A long to identify this session
 * @param storedState: Previous state, if this activity was stopped and is being recreated
 */
class GrantPermissionsViewModel(
    private val app: Application,
    private val packageName: String,
    private val requestedPermissions: List<String>,
    private val sessionId: Long,
    private val storedState: Bundle?
) : ViewModel() {
    private val LOG_TAG = GrantPermissionsViewModel::class.java.simpleName
    private val user = Process.myUserHandle()
    private val packageInfoLiveData = LightPackageInfoLiveData[packageName, user]
    private val safetyLabelInfoLiveData =
        if (SdkLevel.isAtLeastU() && requestedPermissions
                .mapNotNull { PermissionMapping.getGroupOfPlatformPermission(it) }
                .any { PermissionMapping.isSafetyLabelAwarePermissionGroup(it) }) {
            SafetyLabelInfoLiveData[packageName, user]
        } else {
            null
        }
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)!!
    private val permissionPolicy = dpm.getPermissionPolicy(null)
    private val permGroupsToSkip = mutableListOf<String>()
    private var groupStates = mutableMapOf<Pair<String, Boolean>, GroupState>()

    private var autoGrantNotifier: AutoGrantPermissionsNotifier? = null
    private fun getAutoGrantNotifier(): AutoGrantPermissionsNotifier {
        autoGrantNotifier = AutoGrantPermissionsNotifier(app, packageInfo.toPackageInfo(app)!!)
        return autoGrantNotifier!!
    }

    private lateinit var packageInfo: LightPackageInfo

    // All permissions that could possibly be affected by the provided requested permissions, before
    // filtering system fixed, auto grant, etc.
    private var unfilteredAffectedPermissions = requestedPermissions

    private val splitPermissionTargetSdkMap = mutableMapOf<String, Int>()

    private var appPermGroupLiveDatas = mutableMapOf<String, LightAppPermGroupLiveData>()

    /**
     * A class which represents a correctly requested permission group, and the buttons and messages
     * which should be shown with it.
     */
    data class RequestInfo(
        val groupInfo: LightPermGroupInfo,
        val buttonVisibilities: List<Boolean> = List(NEXT_BUTTON) { false },
        val locationVisibilities: List<Boolean> = List(NEXT_LOCATION_DIALOG) { false },
        val message: RequestMessage = RequestMessage.FG_MESSAGE,
        val detailMessage: RequestMessage = RequestMessage.NO_MESSAGE,
        val sendToSettingsImmediately: Boolean = false,
        val openPhotoPicker: Boolean = false,
    ) {
        val groupName = groupInfo.name
    }

    var activityResultCallback: Consumer<Intent>? = null

    /**
     * A LiveData which holds a list of the currently pending RequestInfos
     */
    val requestInfosLiveData = object :
        SmartUpdateMediatorLiveData<List<RequestInfo>>() {
        private val LOG_TAG = GrantPermissionsViewModel::class.java.simpleName
        private val packagePermissionsLiveData = PackagePermissionsLiveData[packageName, user]

        init {
            addSource(packagePermissionsLiveData) { onPackageLoaded() }
            addSource(packageInfoLiveData) { onPackageLoaded() }
            if (safetyLabelInfoLiveData != null) {
                addSource(safetyLabelInfoLiveData) { onPackageLoaded() }
            }

            // Load package state, if available
            onPackageLoaded()
        }

        private fun onPackageLoaded() {
            if (packageInfoLiveData.isStale ||
                packagePermissionsLiveData.isStale ||
                (safetyLabelInfoLiveData != null && safetyLabelInfoLiveData.isStale)) {
                return
            }

            val groups = packagePermissionsLiveData.value
            val pI = packageInfoLiveData.value
            if (groups == null || groups.isEmpty() || pI == null) {
                Log.e(LOG_TAG, "Package $packageName not found")
                value = null
                return
            }
            packageInfo = pI

            if (packageInfo.requestedPermissions.isEmpty() ||
                packageInfo.targetSdkVersion < Build.VERSION_CODES.M) {
                Log.e(LOG_TAG, "Package $packageName has no requested permissions, or " +
                        "is a pre-M app")
                value = null
                return
            }

            val allAffectedPermissions = requestedPermissions.toMutableSet()
            for (requestedPerm in requestedPermissions) {
                allAffectedPermissions.addAll(computeAffectedPermissions(requestedPerm, groups))
            }
            unfilteredAffectedPermissions = allAffectedPermissions.toList()

            setAppPermGroupsLiveDatas(groups.toMutableMap().apply {
                remove(PackagePermissionsLiveData.NON_RUNTIME_NORMAL_PERMS)
            })

            for (splitPerm in app.getSystemService(
                PermissionManager::class.java)!!.splitPermissions) {
                splitPermissionTargetSdkMap[splitPerm.splitPermission] = splitPerm.targetSdk
            }
        }

        private fun setAppPermGroupsLiveDatas(groups: Map<String, List<String>>) {

            val requestedGroups = groups.filter { (_, perms) ->
                perms.any { it in unfilteredAffectedPermissions }
            }

            if (requestedGroups.isEmpty()) {
                Log.e(LOG_TAG, "None of " +
                    "$unfilteredAffectedPermissions in $groups")
                value = null
                return
            }

            val getLiveDataFun = { groupName: String ->
                LightAppPermGroupLiveData[packageName, groupName, user]
            }
            setSourcesToDifference(requestedGroups.keys, appPermGroupLiveDatas, getLiveDataFun)
        }

        override fun onUpdate() {
            if (appPermGroupLiveDatas.any { it.value.isStale }) {
                return
            }
            var newGroups = false
            for ((groupName, groupLiveData) in appPermGroupLiveDatas) {
                val appPermGroup = groupLiveData.value
                if (appPermGroup == null || groupName in permGroupsToSkip) {
                    if (appPermGroup == null) {
                        Log.e(LOG_TAG, "Group $packageName $groupName invalid")
                    }
                    groupStates[groupName to true]?.state = STATE_SKIPPED
                    groupStates[groupName to false]?.state = STATE_SKIPPED
                    continue
                }

                packageInfo = appPermGroup.packageInfo

                val states = groupStates.filter { it.key.first == groupName }
                if (states.isNotEmpty()) {
                    for ((key, state) in states) {
                        val allAffectedGranted = state.affectedPermissions.all { perm ->
                            appPermGroup.permissions[perm]?.isGrantedIncludingAppOp == true &&
                                appPermGroup.permissions[perm]?.isRevokeWhenRequested == false
                        }
                        if (allAffectedGranted) {
                            groupStates[key]!!.state = STATE_ALLOWED
                        }
                    }
                } else {
                    newGroups = true
                }
            }

            if (newGroups) {
                groupStates = getRequiredGroupStates(
                    appPermGroupLiveDatas.mapNotNull { it.value.value })
            }
            setRequestInfosFromGroupStates()
        }

        private fun setRequestInfosFromGroupStates() {
            val requestInfos = mutableListOf<RequestInfo>()
            for ((key, groupState) in groupStates) {
                val groupInfo = groupState.group.permGroupInfo
                val (groupName, isBackground) = key
                if (groupState.state != STATE_UNKNOWN) {
                    continue
                }
                val fgState = groupStates[groupName to false]
                val bgState = groupStates[groupName to true]
                var needFgPermissions = false
                var needBgPermissions = false
                var isFgUserSet = false
                var isBgUserSet = false
                var minSdkForOrderedSplitPermissions = Build.VERSION_CODES.R
                if (fgState?.group != null) {
                    val fgGroup = fgState.group
                    for (perm in fgState.affectedPermissions) {
                        minSdkForOrderedSplitPermissions = maxOf(minSdkForOrderedSplitPermissions,
                                splitPermissionTargetSdkMap.getOrDefault(perm, 0))
                        if (fgGroup.permissions[perm]?.isGrantedIncludingAppOp == false) {
                            // If any of the requested permissions is not granted,
                            // needFgPermissions = true
                            needFgPermissions = true
                            // If any of the requested permission's UserSet is true and the
                            // permission is not granted, isFgUserSet = true.
                            if (fgGroup.permissions[perm]?.isUserSet == true) {
                                isFgUserSet = true
                            }
                        }
                    }
                }
                if (bgState?.group?.background?.isGranted == false) {
                    needBgPermissions = true
                    isBgUserSet = bgState.group.background.isUserSet
                }

                val buttonVisibilities = MutableList(NEXT_BUTTON) { false }
                buttonVisibilities[ALLOW_BUTTON] = true
                buttonVisibilities[DENY_BUTTON] = true
                buttonVisibilities[ALLOW_ONE_TIME_BUTTON] =
                    PermissionMapping.supportsOneTimeGrant(groupName)
                var message = RequestMessage.FG_MESSAGE
                // Whether or not to use the foreground, background, or no detail message.
                // null ==
                var detailMessage = RequestMessage.NO_MESSAGE

                if (KotlinUtils.isPhotoPickerPromptEnabled() &&
                    groupState.group.permGroupName == READ_MEDIA_VISUAL &&
                    groupState.group.packageInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) {
                    // If the USER_SELECTED permission is user fixed and granted, or the app is only
                    // requesting USER_SELECTED, direct straight to photo picker
                    val userPerm = groupState.group.permissions[READ_MEDIA_VISUAL_USER_SELECTED]
                    if ((userPerm?.isUserFixed == true && userPerm.isGrantedIncludingAppOp) ||
                        groupState.affectedPermissions == listOf(READ_MEDIA_VISUAL_USER_SELECTED)) {
                        requestInfos.add(RequestInfo(groupInfo, openPhotoPicker = true))
                        continue
                    } else if (isPartialStorageGrant(groupState.group)) {
                        // More photos dialog
                        message = RequestMessage.MORE_PHOTOS_MESSAGE
                        buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = false
                        buttonVisibilities[DENY_BUTTON] = false
                        buttonVisibilities[DONT_ALLOW_MORE_SELECTED_BUTTON] = true
                    } else {
                        // Standard photos dialog
                        buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = isFgUserSet
                        buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                    }
                    buttonVisibilities[ALLOW_SELECTED_BUTTON] = true
                    buttonVisibilities[ALLOW_BUTTON] = false
                    buttonVisibilities[ALLOW_ALL_BUTTON] = true
                } else if (groupState.group.packageInfo.targetSdkVersion >=
                        minSdkForOrderedSplitPermissions) {
                    if (isBackground || Utils.hasPermWithBackgroundModeCompat(groupState.group)) {
                        if (needFgPermissions) {
                            if (needBgPermissions) {
                                if (groupState.group.permGroupName
                                                .equals(Manifest.permission_group.CAMERA) ||
                                        groupState.group.permGroupName
                                                .equals(Manifest.permission_group.MICROPHONE)) {
                                    if (groupState.group.packageInfo.targetSdkVersion >=
                                            Build.VERSION_CODES.S) {
                                        Log.e(LOG_TAG,
                                                "For S apps, background permissions must be " +
                                                "requested after foreground permissions are" +
                                                        " already granted")
                                        value = null
                                        return
                                    } else {
                                        // Case: sdk < S, BG&FG mic/camera permission requested
                                        buttonVisibilities[ALLOW_BUTTON] = false
                                        buttonVisibilities[ALLOW_FOREGROUND_BUTTON] = true
                                        buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                                        buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] =
                                                isFgUserSet
                                        if (needBgPermissions) {
                                            // Case: sdk < R, BG/FG permission requesting both
                                            message = RequestMessage.BG_MESSAGE
                                            detailMessage = RequestMessage.BG_MESSAGE
                                        }
                                    }
                                } else {
                                    // Shouldn't be reached as background must be requested as a
                                    // singleton
                                    Log.e(LOG_TAG, "For R+ apps, background permissions must be " +
                                            "requested after foreground permissions are already" +
                                            " granted")
                                    value = null
                                    return
                                }
                            } else {
                                buttonVisibilities[ALLOW_BUTTON] = false
                                buttonVisibilities[ALLOW_FOREGROUND_BUTTON] = true
                                buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                                buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = isFgUserSet
                            }
                        } else if (needBgPermissions) {
                            // Case: sdk >= R, BG/FG permission requesting BG only
                            if (storedState != null && storedState.containsKey(getInstanceStateKey(
                                    groupInfo.name, groupState.isBackground))) {
                                // If we're restoring state, and we had this groupInfo in our
                                // previous state, that means that we likely sent the user to
                                // settings already. Don't send the user back.
                                permGroupsToSkip.add(groupInfo.name)
                                groupState.state = STATE_SKIPPED
                            } else {
                                requestInfos.add(RequestInfo(
                                    groupInfo, sendToSettingsImmediately = true))
                            }
                            continue
                        } else {
                            // Not reached as the permissions should be auto-granted
                            value = null
                            return
                        }
                    } else {
                        // Case: sdk >= R, Requesting normal permission
                        buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                        buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = isFgUserSet
                    }
                } else {
                    if (isBackground || Utils.hasPermWithBackgroundModeCompat(groupState.group)) {
                        if (needFgPermissions) {
                            // Case: sdk < R, BG/FG permission requesting both or FG only
                            buttonVisibilities[ALLOW_BUTTON] = false
                            buttonVisibilities[ALLOW_FOREGROUND_BUTTON] = true
                            buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                            buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = isFgUserSet
                            if (needBgPermissions) {
                                // Case: sdk < R, BG/FG permission requesting both
                                message = RequestMessage.BG_MESSAGE
                                detailMessage = RequestMessage.BG_MESSAGE
                            }
                        } else if (needBgPermissions) {
                            // Case: sdk < R, BG/FG permission requesting BG only
                            if (!groupState.group.foreground.isGranted) {
                                Log.e(LOG_TAG, "Background permissions can't be requested " +
                                        "solely before foreground permissions are granted.")
                                value = null
                                return
                            }
                            message = RequestMessage.UPGRADE_MESSAGE
                            detailMessage = RequestMessage.UPGRADE_MESSAGE
                            buttonVisibilities[ALLOW_BUTTON] = false
                            buttonVisibilities[DENY_BUTTON] = false
                            buttonVisibilities[ALLOW_ONE_TIME_BUTTON] = false
                            if (groupState.group.isOneTime) {
                                buttonVisibilities[NO_UPGRADE_OT_BUTTON] = !isBgUserSet
                                buttonVisibilities[NO_UPGRADE_OT_AND_DONT_ASK_AGAIN_BUTTON] =
                                    isBgUserSet
                            } else {
                                buttonVisibilities[NO_UPGRADE_BUTTON] = !isBgUserSet
                                buttonVisibilities[NO_UPGRADE_AND_DONT_ASK_AGAIN_BUTTON] =
                                    isBgUserSet
                            }
                        } else {
                            // Not reached as the permissions should be auto-granted
                            value = null
                            return
                        }
                    } else {
                        // If no permissions needed, do nothing
                        if (!needFgPermissions && !needBgPermissions) {
                            value = null
                            return
                        }
                        // Case: sdk < R, Requesting normal permission
                        buttonVisibilities[DENY_BUTTON] = !isFgUserSet
                        buttonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON] = isFgUserSet
                    }
                }
                buttonVisibilities[LINK_TO_SETTINGS] =
                    detailMessage != RequestMessage.NO_MESSAGE

                // Show location permission dialogs based on location permissions
                val locationVisibilities = MutableList(NEXT_LOCATION_DIALOG) { false }
                if (groupState.group.permGroupName == LOCATION && isLocationAccuracyEnabled() &&
                    packageInfo.targetSdkVersion >= Build.VERSION_CODES.S) {
                    if (needFgPermissions) {
                        locationVisibilities[LOCATION_ACCURACY_LAYOUT] = true
                        if (fgState != null &&
                                fgState.affectedPermissions.contains(ACCESS_FINE_LOCATION)) {
                            val coarseLocationPerm =
                                groupState.group.allPermissions[ACCESS_COARSE_LOCATION]
                            if (coarseLocationPerm?.isGrantedIncludingAppOp == true) {
                                // Upgrade flow
                                locationVisibilities[DIALOG_WITH_FINE_LOCATION_ONLY] = true
                                message = RequestMessage.FG_FINE_LOCATION_MESSAGE
                                // If COARSE was granted one time, hide 'While in use' button
                                if (coarseLocationPerm.isOneTime) {
                                    buttonVisibilities[ALLOW_FOREGROUND_BUTTON] = false
                                }
                            } else {
                                if (!fgState.affectedPermissions.contains(ACCESS_COARSE_LOCATION)) {
                                    Log.e(LOG_TAG, "ACCESS_FINE_LOCATION must be requested " +
                                            "with ACCESS_COARSE_LOCATION.")
                                    value = null
                                    return
                                }
                                // Normal flow with both Coarse and Fine locations
                                locationVisibilities[DIALOG_WITH_BOTH_LOCATIONS] = true
                                // Steps to decide location accuracy default state
                                // 1. If none of the FINE and COARSE isSelectedLocationAccuracy
                                //    flags is set, then use default precision from device config.
                                // 2. Otherwise set to whichever isSelectedLocationAccuracy is true.
                                val fineLocationPerm =
                                        groupState.group.allPermissions[ACCESS_FINE_LOCATION]
                                if (coarseLocationPerm?.isSelectedLocationAccuracy == false &&
                                        fineLocationPerm?.isSelectedLocationAccuracy == false) {
                                    if (getDefaultPrecision()) {
                                        locationVisibilities[FINE_RADIO_BUTTON] = true
                                    } else {
                                        locationVisibilities[COARSE_RADIO_BUTTON] = true
                                    }
                                } else if (coarseLocationPerm?.isSelectedLocationAccuracy == true) {
                                    locationVisibilities[COARSE_RADIO_BUTTON] = true
                                } else {
                                    locationVisibilities[FINE_RADIO_BUTTON] = true
                                }
                            }
                        } else if (fgState != null && fgState.affectedPermissions
                                        .contains(ACCESS_COARSE_LOCATION)) {
                            // Request Coarse only
                            locationVisibilities[DIALOG_WITH_COARSE_LOCATION_ONLY] = true
                            message = RequestMessage.FG_COARSE_LOCATION_MESSAGE
                        }
                    }
                }

                if (SdkLevel.isAtLeastT()) {
                    // If app is T+, requests for the STORAGE group are ignored
                    if (packageInfo.targetSdkVersion > Build.VERSION_CODES.S_V2 &&
                        groupState.group.permGroupName == Manifest.permission_group.STORAGE) {
                        continue
                    }
                    // If app is <T and requests STORAGE, grant dialogs has special text
                    if (groupState.group.permGroupName in
                        PermissionMapping.STORAGE_SUPERGROUP_PERMISSIONS) {
                        if (packageInfo.targetSdkVersion < Build.VERSION_CODES.Q) {
                            message = RequestMessage.STORAGE_SUPERGROUP_MESSAGE_PRE_Q
                        } else if (packageInfo.targetSdkVersion <= Build.VERSION_CODES.S_V2) {
                            message = RequestMessage.STORAGE_SUPERGROUP_MESSAGE_Q_TO_S
                        }
                    }
                }

                val safetyLabel = safetyLabelInfoLiveData?.value?.safetyLabel
                val showPermissionRationale =
                    shouldShowPermissionRationale(safetyLabel, groupState.group.permGroupName)
                buttonVisibilities[LINK_TO_PERMISSION_RATIONALE] = showPermissionRationale

                requestInfos.add(RequestInfo(
                    groupInfo,
                    buttonVisibilities,
                    locationVisibilities,
                    message,
                    detailMessage))
            }

            sortPermissionGroups(requestInfos)

            value = if (requestInfos.any { it.sendToSettingsImmediately } &&
                requestInfos.size > 1) {
                Log.e(LOG_TAG, "For R+ apps, background permissions must be requested " +
                    "individually")
                null
            } else {
                requestInfos
            }
        }
    }

    fun sortPermissionGroups(requestInfos: MutableList<RequestInfo>) {
        requestInfos.sortWith { rhs, lhs ->
            val rhsHasOneTime = rhs.buttonVisibilities[ALLOW_ONE_TIME_BUTTON]
            val lhsHasOneTime = lhs.buttonVisibilities[ALLOW_ONE_TIME_BUTTON]
            if (rhsHasOneTime && !lhsHasOneTime) {
                -1
            } else if ((!rhsHasOneTime && lhsHasOneTime) ||
                isHealthPermissionGroup(rhs.groupName)
            ) {
                1
            } else {
                rhs.groupName.compareTo(lhs.groupName)
            }
        }
    }

    private fun shouldShowPermissionRationale(
        safetyLabel: SafetyLabel?,
        permissionGroupName: String?
    ): Boolean {
        if (safetyLabel == null || permissionGroupName == null) {
            return false
        }

        val purposes = SafetyLabelUtils.getSafetyLabelSharingPurposesForGroup(safetyLabel,
            permissionGroupName)
        return purposes.isNotEmpty()
    }

    /**
     * Converts a list of LightAppPermGroups into a list of GroupStates
     */
    private fun getRequiredGroupStates(
        groups: List<LightAppPermGroup>
    ): MutableMap<Pair<String, Boolean>, GroupState> {
        val groupStates = mutableMapOf<Pair<String, Boolean>, GroupState>()
        val filteredPermissions = unfilteredAffectedPermissions.filter { perm ->
            val group = getGroupWithPerm(perm, groups)
            group != null && isPermissionGrantableAndNotFixed(perm, group)
        }
        for (perm in filteredPermissions) {
            val group = getGroupWithPerm(perm, groups)!!

            val isBackground = perm in group.backgroundPermNames
            val groupStateInfo = groupStates.getOrPut(group.permGroupName to isBackground) {
                GroupState(group, isBackground)
            }

            var currGroupState = groupStateInfo.state
            if (storedState != null && currGroupState != STATE_UNKNOWN) {
                currGroupState = storedState.getInt(getInstanceStateKey(group.permGroupName,
                    isBackground), STATE_UNKNOWN)
            }

            val otherGroupPermissions = filteredPermissions.filter { it in group.permissions }
            val groupStateOfPerm = getGroupState(perm, group, otherGroupPermissions)
            if (groupStateOfPerm != STATE_UNKNOWN) {
                currGroupState = groupStateOfPerm
            }

            if (group.permGroupName in permGroupsToSkip) {
                currGroupState = STATE_SKIPPED
            }

            if (currGroupState != STATE_UNKNOWN) {
                groupStateInfo.state = currGroupState
            }
            // If we saved state, load it
            groupStateInfo.affectedPermissions.add(perm)
        }
        return groupStates
    }

    /**
     * Get the actually requested permissions when a permission is requested.
     *
     * >In some cases requesting to grant a single permission requires the system to grant
     * additional permissions. E.g. before N-MR1 a single permission of a group caused the whole
     * group to be granted. Another case are permissions that are split into two. For apps that
     * target an SDK before the split, this method automatically adds the split off permission.
     *
     * @param perm The requested permission
     *
     * @return The actually requested permissions
     */
    private fun computeAffectedPermissions(
        perm: String,
        appPermissions: Map<String, List<String>>
    ): List<String> {
        val requestingAppTargetSDK = packageInfo.targetSdkVersion

        // If a permission is split, all permissions the original permission is split into are
        // affected
        val extendedBySplitPerms = mutableListOf(perm)

        val splitPerms = app.getSystemService(PermissionManager::class.java)!!.splitPermissions
        for (splitPerm in splitPerms) {

            if (requestingAppTargetSDK < splitPerm.targetSdk && perm == splitPerm.splitPermission) {
                extendedBySplitPerms.addAll(splitPerm.newPermissions)
            }
        }

        // For <= N_MR1 apps all permissions of the groups of the requested permissions are affected
        if (requestingAppTargetSDK <= Build.VERSION_CODES.N_MR1) {
            val extendedBySplitPermsAndGroup = mutableListOf<String>()

            for (splitPerm in extendedBySplitPerms) {
                val groups = appPermissions.filter { splitPerm in it.value }
                if (groups.isEmpty()) {
                    continue
                }

                val permissionsInGroup = groups.values.first()
                for (permissionInGroup in permissionsInGroup) {
                    extendedBySplitPermsAndGroup.add(permissionInGroup)
                }
            }

            return extendedBySplitPermsAndGroup
        } else {
            return extendedBySplitPerms
        }
    }

    private fun isPermissionGrantableAndNotFixed(perm: String, group: LightAppPermGroup): Boolean {

        // If the permission is restricted it does not show in the UI and
        // is not added to the group at all, so check that first.
        if (perm in group.packageInfo.requestedPermissions && perm !in group.permissions) {
            reportRequestResult(perm,
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_RESTRICTED_PERMISSION)
            return false
        }

        if (HEALTH_PERMISSION_GROUP == group.permGroupName) {
            return !(group.permissions[perm]?.isUserFixed ?: true)
        }

        val subGroup = if (perm in group.backgroundPermNames) {
            group.background
        } else {
            group.foreground
        }

        val lightPermission = group.permissions[perm] ?: return false

        if (!subGroup.isGrantable) {
            reportRequestResult(perm, PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED)
            // Skip showing groups that we know cannot be granted.
            return false
        } else if (subGroup.isUserFixed) {
            if (perm == ACCESS_COARSE_LOCATION) {
                val coarsePerm = group.permissions[perm]
                if (coarsePerm != null && !coarsePerm.isUserFixed) {
                    // If the location group is user fixed but ACCESS_COARSE_LOCATION is not, then
                    // ACCESS_FINE_LOCATION must be user fixed. In this case ACCESS_COARSE_LOCATION
                    // is still grantable.
                    return true
                }
            } else if (perm in getPartialStorageGrantPermissionsForGroup(group) &&
                lightPermission.isGrantedIncludingAppOp) {
                // If a partial storage permission is granted as fixed, we should immediately show
                // the photo picker
                return true
            }
            reportRequestResult(perm,
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_USER_FIXED)
            return false
        } else if (subGroup.isPolicyFixed && !subGroup.isGranted || lightPermission.isPolicyFixed) {
            reportRequestResult(perm,
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED_POLICY_FIXED)
            return false
        }

        return true
    }

    private fun getGroupState(
        perm: String,
        group: LightAppPermGroup,
        groupRequestedPermissions: List<String>
    ): Int {
        val policyState = getStateFromPolicy(perm, group)
        if (policyState != STATE_UNKNOWN) {
            return policyState
        }

        if (perm == POST_NOTIFICATIONS &&
            packageInfo.targetSdkVersion <= Build.VERSION_CODES.S_V2 &&
            group.foreground.isUserSet) {
            return STATE_SKIPPED
        } else if (perm == READ_MEDIA_VISUAL_USER_SELECTED) {
            val partialPerms = getPartialStorageGrantPermissionsForGroup(group)
            val otherRequestedPerms = unfilteredAffectedPermissions.filter { otherPerm ->
                otherPerm in group.permissions && otherPerm !in partialPerms
            }
            if (otherRequestedPerms.isEmpty()) {
                // If the app requested USER_SELECTED while not supporting the photo picker, or if
                // the app explicitly requested only USER_SELECTED and/or ACCESS_MEDIA_LOCATION,
                // then skip the request
                return STATE_SKIPPED
            }
        }

        val isBackground = perm in group.backgroundPermNames

        val hasForegroundRequest = groupRequestedPermissions.any {
            it !in group.backgroundPermNames
        }

        // Do not attempt to grant background access if foreground access is not either already
        // granted or requested
        if (isBackground && !group.foreground.isGrantedExcludingRWROrAllRWR &&
            !hasForegroundRequest) {
            Log.w(LOG_TAG, "Cannot grant $perm as the matching foreground permission is not " +
                "already granted.")
            val affectedPermissions = groupRequestedPermissions.filter {
                it in group.backgroundPermNames
            }
            reportRequestResult(affectedPermissions,
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__IGNORED)
            return STATE_SKIPPED
        }

        if ((isBackground && group.background.isGrantedExcludingRWROrAllRWR ||
            !isBackground && group.foreground.isGrantedExcludingRWROrAllRWR) &&
            canAutoGrantWholeGroup(group)) {
            if (group.permissions[perm]?.isGrantedIncludingAppOp == false) {
                if (isBackground) {
                    grantBackgroundRuntimePermissions(app, group, listOf(perm))
                } else {
                    grantForegroundRuntimePermissions(app, group, listOf(perm), group.isOneTime)
                }
                KotlinUtils.setGroupFlags(app, group, FLAG_PERMISSION_USER_SET to false,
                    FLAG_PERMISSION_USER_FIXED to false, filterPermissions = listOf(perm))
                reportRequestResult(perm,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED)
            }

            return if (storedState == null) {
                STATE_SKIPPED
            } else {
                STATE_ALLOWED
            }
        }
        return STATE_UNKNOWN
    }

    /**
     * Determines if remaining permissions in the group can be auto granted based on
     * granted permissions in the group.
     */
    private fun canAutoGrantWholeGroup(group: LightAppPermGroup): Boolean {
        // If FINE location is not granted, do not grant it automatically when COARSE
        // location is already granted.
        if (group.permGroupName == LOCATION &&
            group.allPermissions[ACCESS_FINE_LOCATION]?.isGrantedIncludingAppOp == false) {
            return false
        }
        // If READ_MEDIA_VISUAL_USER_SELECTED is the only permission in the group that is granted,
        // do not grant.
        if (isPartialStorageGrant(group) || HEALTH_PERMISSION_GROUP == group.permGroupName) {
            return false
        }
        return true
    }

    /**
     * A partial storage grant happens when:
     * An app which doesn't support the photo picker has READ_MEDIA_VISUAL_USER_SELECTED granted, or
     * An app which does support the photo picker has READ_MEDIA_VISUAL_USER_SELECTED and/or
     * ACCESS_MEDIA_LOCATION granted
     */
    private fun isPartialStorageGrant(group: LightAppPermGroup): Boolean {
        if (!KotlinUtils.isPhotoPickerPromptEnabled() || group.permGroupName != READ_MEDIA_VISUAL) {
            return false
        }

        val partialPerms = getPartialStorageGrantPermissionsForGroup(group)
        return group.isGranted && group.permissions.values.all {
            it.name in partialPerms || (it.name !in partialPerms && !it.isGrantedIncludingAppOp)
        }
    }

    private fun getStateFromPolicy(perm: String, group: LightAppPermGroup): Int {
        val isBackground = perm in group.backgroundPermNames
        var skipGroup = false
        var state = STATE_UNKNOWN
        when (permissionPolicy) {
            DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT -> {
                if (AdminRestrictedPermissionsUtils.mayAdminGrantPermission(
                                app, perm, user.identifier)) {
                    if (isBackground) {
                        grantBackgroundRuntimePermissions(app, group, listOf(perm))
                    } else {
                        grantForegroundRuntimePermissions(app, group, listOf(perm))
                    }
                    KotlinUtils.setGroupFlags(app, group, FLAG_PERMISSION_POLICY_FIXED to true,
                            FLAG_PERMISSION_USER_SET to false, FLAG_PERMISSION_USER_FIXED to false,
                            filterPermissions = listOf(perm))
                    state = STATE_ALLOWED
                    skipGroup = true

                    getAutoGrantNotifier().onPermissionAutoGranted(perm)
                    reportRequestResult(perm,
                            PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_GRANTED)
                }
            }

            DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY -> {
                if (group.permissions[perm]?.isPolicyFixed == false) {
                    KotlinUtils.setGroupFlags(app, group, FLAG_PERMISSION_POLICY_FIXED to true,
                        FLAG_PERMISSION_USER_SET to false, FLAG_PERMISSION_USER_FIXED to false,
                        filterPermissions = listOf(perm))
                }
                state = STATE_DENIED
                skipGroup = true

                reportRequestResult(perm,
                    PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__AUTO_DENIED)
            }
        }
        if (skipGroup && storedState == null) {
            return STATE_SKIPPED
        }
        return state
    }

    /**
     * Upon the user clicking a button, grant permissions, if applicable.
     *
     * @param groupName The name of the permission group which was changed
     * @param affectedForegroundPermissions The name of the foreground permission which was changed
     * @param result The choice the user made regarding the group.
     */
    fun onPermissionGrantResult(
        groupName: String?,
        affectedForegroundPermissions: List<String>?,
        result: Int
    ) {
        onPermissionGrantResult(groupName, affectedForegroundPermissions, result, false)
    }

    private fun onPermissionGrantResult(
        groupName: String?,
        affectedForegroundPermissions: List<String>?,
        result: Int,
        alreadyRequestedStorageGroupsIfNeeded: Boolean
    ) {
        if (groupName == null) {
            return
        }

        // If this is a legacy app, and a storage group is requested: request all storage groups
        if (!alreadyRequestedStorageGroupsIfNeeded &&
            groupName in PermissionMapping.STORAGE_SUPERGROUP_PERMISSIONS &&
            packageInfo.targetSdkVersion <= Build.VERSION_CODES.S_V2) {
            for (storageGroupName in PermissionMapping.STORAGE_SUPERGROUP_PERMISSIONS) {
                val groupPerms = appPermGroupLiveDatas[storageGroupName]
                    ?.value?.allPermissions?.keys?.toList()
                onPermissionGrantResult(storageGroupName, groupPerms, result, true)
            }
            return
        }

        val foregroundGroupState = groupStates[groupName to false]
        val backgroundGroupState = groupStates[groupName to true]
        when (result) {
            CANCELED -> {
                if (foregroundGroupState != null) {
                    reportRequestResult(foregroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_IGNORED)
                    foregroundGroupState.state = STATE_SKIPPED
                }
                if (backgroundGroupState != null) {
                    reportRequestResult(backgroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_IGNORED)
                    backgroundGroupState.state = STATE_SKIPPED
                }
                requestInfosLiveData.update()
                return
            }
            GRANTED_ALWAYS -> {
                if (foregroundGroupState != null) {
                    onPermissionGrantResultSingleState(foregroundGroupState,
                        affectedForegroundPermissions, granted = true, isOneTime = false,
                        doNotAskAgain = false)
                }
                if (backgroundGroupState != null) {
                    onPermissionGrantResultSingleState(backgroundGroupState,
                        affectedForegroundPermissions, granted = true, isOneTime = false,
                        doNotAskAgain = false)
                }
            }
            GRANTED_FOREGROUND_ONLY -> {
                if (foregroundGroupState != null) {
                    onPermissionGrantResultSingleState(foregroundGroupState,
                        affectedForegroundPermissions, granted = true, isOneTime = false,
                        doNotAskAgain = false)
                }
                if (backgroundGroupState != null) {
                    onPermissionGrantResultSingleState(backgroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = false,
                        doNotAskAgain = false)
                }
            }
            GRANTED_ONE_TIME -> {
                if (foregroundGroupState != null) {
                    onPermissionGrantResultSingleState(foregroundGroupState,
                        affectedForegroundPermissions, granted = true, isOneTime = true,
                        doNotAskAgain = false)
                }
                if (backgroundGroupState != null) {
                    onPermissionGrantResultSingleState(backgroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = true,
                        doNotAskAgain = false)
                }
            }
            GRANTED_USER_SELECTED, DENIED_MORE -> {
                if (foregroundGroupState != null) {
                    grantUserSelectedVisualGroupPermissions(foregroundGroupState)
                }
            }
            DENIED -> {
                if (foregroundGroupState != null) {
                    onPermissionGrantResultSingleState(foregroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = false,
                        doNotAskAgain = false)
                }
                if (backgroundGroupState != null) {
                    onPermissionGrantResultSingleState(backgroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = false,
                        doNotAskAgain = false)
                }
            }
            DENIED_DO_NOT_ASK_AGAIN -> {
                if (foregroundGroupState != null) {
                    onPermissionGrantResultSingleState(foregroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = false,
                        doNotAskAgain = true)
                }
                if (backgroundGroupState != null) {
                    onPermissionGrantResultSingleState(backgroundGroupState,
                        affectedForegroundPermissions, granted = false, isOneTime = false,
                        doNotAskAgain = true)
                }
            }
        }
    }

    private fun grantUserSelectedVisualGroupPermissions(groupState: GroupState) {
        val userSelectedPerm =
            groupState.group.permissions[READ_MEDIA_VISUAL_USER_SELECTED] ?: return
        if (userSelectedPerm.isImplicit) {
            val nonSelectedPerms = groupState.group.permissions.keys
                .filter { it != READ_MEDIA_VISUAL_USER_SELECTED }
            // If the permission is implicit, grant USER_SELECTED as user set, and all other
            // permissions as one time, and without app ops.
            grantForegroundRuntimePermissions(app, groupState.group,
                listOf(READ_MEDIA_VISUAL_USER_SELECTED))
            grantForegroundRuntimePermissions(app, groupState.group,
                nonSelectedPerms, isOneTime = true, userFixed = false, withoutAppOps = true)
            val appPermGroup = AppPermissionGroup.create(app, packageName,
            groupState.group.permGroupName, groupState.group.userHandle, false)
            appPermGroup.setSelfRevoked()
            appPermGroup.persistChanges(false, null, nonSelectedPerms.toSet())
        } else {
            val partialPerms = getPartialStorageGrantPermissionsForGroup(groupState.group).filter {
                it in groupState.affectedPermissions
            }
            val nonSelectedPerms = groupState.affectedPermissions.filter { it !in partialPerms }
            val setUserFixed = userSelectedPerm.isUserFixed || userSelectedPerm.isUserSet
            grantForegroundRuntimePermissions(app, groupState.group,
                partialPerms.toList(), userFixed = setUserFixed)
            revokeForegroundRuntimePermissions(app, groupState.group,
                userFixed = setUserFixed, oneTime = false, filterPermissions = nonSelectedPerms)
        }
        groupState.state = STATE_ALLOWED
        reportButtonClickResult(groupState, true,
            PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__PHOTOS_SELECTED)
    }

    @SuppressLint("NewApi")
    private fun onPermissionGrantResultSingleState(
        groupState: GroupState,
        affectedForegroundPermissions: List<String>?,
        granted: Boolean,
        isOneTime: Boolean,
        doNotAskAgain: Boolean
    ) {
        if (groupState.state != STATE_UNKNOWN) {
            // We already dealt with this group, don't re-grant/re-revoke
            return
        }
        val result: Int
        if (granted) {
            result = if (isOneTime) {
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_ONE_TIME
            } else {
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED
            }
            if (groupState.isBackground) {
                grantBackgroundRuntimePermissions(app, groupState.group,
                    groupState.affectedPermissions)
            } else {
                if (affectedForegroundPermissions == null) {
                    grantForegroundRuntimePermissions(app, groupState.group,
                        groupState.affectedPermissions, isOneTime)
                    // This prevents weird flag state when app targetSDK switches from S+ to R-
                    if (groupState.affectedPermissions.contains(ACCESS_FINE_LOCATION)) {
                        KotlinUtils.setFlagsWhenLocationAccuracyChanged(
                                app, groupState.group, true)
                    }
                } else {
                    val newGroup = grantForegroundRuntimePermissions(app,
                            groupState.group, affectedForegroundPermissions, isOneTime)
                    if (!isOneTime || newGroup.isOneTime) {
                        KotlinUtils.setFlagsWhenLocationAccuracyChanged(app, newGroup,
                                affectedForegroundPermissions.contains(ACCESS_FINE_LOCATION))
                    }
                }
            }
            groupState.state = STATE_ALLOWED
        } else {
            if (groupState.isBackground) {
                revokeBackgroundRuntimePermissions(app, groupState.group,
                    userFixed = doNotAskAgain, filterPermissions = groupState.affectedPermissions)
            } else {
                if (affectedForegroundPermissions == null ||
                        affectedForegroundPermissions.contains(ACCESS_COARSE_LOCATION)) {
                    revokeForegroundRuntimePermissions(app, groupState.group,
                        userFixed = doNotAskAgain,
                        filterPermissions = groupState.affectedPermissions, oneTime = isOneTime)
                } else {
                    revokeForegroundRuntimePermissions(app, groupState.group,
                        userFixed = doNotAskAgain,
                        filterPermissions = affectedForegroundPermissions, oneTime = isOneTime)
                }
            }
            result = if (doNotAskAgain) {
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE
            } else {
                PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED
            }
            groupState.state = STATE_DENIED
        }
        reportButtonClickResult(groupState, granted, result)
    }

    private fun reportButtonClickResult(groupState: GroupState, granted: Boolean, result: Int) {
        reportRequestResult(groupState.affectedPermissions, result)
        // group state has changed, reload liveData
        requestInfosLiveData.update()
        PermissionDecisionStorageImpl.recordPermissionDecision(app.applicationContext,
            packageName, groupState.group.permGroupName, granted)
        PermissionChangeStorageImpl.recordPermissionChange(packageName)
        if (granted) {
            startDrivingDecisionReminderServiceIfNecessary(groupState.group.permGroupName)
        }
    }

    /**
     * When distraction optimization is required (the vehicle is in motion), the user may want to
     * review their permission grants when they are less distracted.
     */
    private fun startDrivingDecisionReminderServiceIfNecessary(permGroupName: String) {
        if (!DeviceUtils.isAuto(app.applicationContext)) {
            return
        }
        DrivingDecisionReminderService.startServiceIfCurrentlyRestricted(
            Utils.getUserContext(app, user), packageName, permGroupName)
    }

    private fun getGroupWithPerm(
        perm: String,
        groups: List<LightAppPermGroup>
    ): LightAppPermGroup? {
        val groupsWithPerm = groups.filter { perm in it.permissions }
        if (groupsWithPerm.isEmpty()) {
            return null
        }
        return groupsWithPerm.first()
    }

    /**
     * An internal class which represents the state of a current AppPermissionGroup grant request.
     */
    internal class GroupState(
        internal val group: LightAppPermGroup,
        internal val isBackground: Boolean,
        internal val affectedPermissions: MutableList<String> = mutableListOf(),
        internal var state: Int = STATE_UNKNOWN
    ) {
        override fun toString(): String {
            val stateStr: String = when (state) {
                STATE_UNKNOWN -> "unknown"
                STATE_ALLOWED -> "granted"
                STATE_DENIED -> "denied"
                else -> "skipped"
            }
            return "${group.permGroupName} $isBackground $stateStr $affectedPermissions"
        }
    }

    private fun reportRequestResult(permissions: List<String>, result: Int) {
        for (perm in permissions) {
            reportRequestResult(perm, result)
        }
    }

    /**
     * Report the result of a grant of a permission.
     *
     * @param permission The permission that was granted or denied
     * @param result The permission grant result
     */
    private fun reportRequestResult(permission: String, result: Int) {
        val isImplicit = permission !in requestedPermissions
        val isPermissionRationaleShown = shouldShowPermissionRationale(
            safetyLabelInfoLiveData?.value?.safetyLabel,
            PermissionMapping.getGroupOfPlatformPermission(permission))

        Log.v(LOG_TAG, "Permission grant result requestId=$sessionId " +
            "callingUid=${packageInfo.uid} callingPackage=$packageName permission=$permission " +
            "isImplicit=$isImplicit result=$result " +
            "isPermissionRationaleShown=$isPermissionRationaleShown")

        PermissionControllerStatsLog.write(
            PERMISSION_GRANT_REQUEST_RESULT_REPORTED, sessionId,
            packageInfo.uid, packageName, permission, isImplicit, result,
            isPermissionRationaleShown)
    }

    /**
     * Save the group states of the view model, to allow for state restoration after lifecycle
     * events
     *
     * @param outState The bundle in which to store state
     */
    fun saveInstanceState(outState: Bundle) {
        for ((groupKey, groupState) in groupStates) {
            val (groupName, isBackground) = groupKey
            outState.putInt(getInstanceStateKey(groupName, isBackground), groupState.state)
        }
    }

    /**
     * Determine if the activity should return permission state to the caller
     *
     * @return Whether or not state should be returned. False only if the package is pre-M, true
     * otherwise.
     */
    fun shouldReturnPermissionState(): Boolean {
        return if (packageInfoLiveData.value != null) {
            packageInfoLiveData.value!!.targetSdkVersion >= Build.VERSION_CODES.M
        } else {
            // Should not be reached, as this method shouldn't be called before data is passed to
            // the activity for the first time
            try {
                Utils.getUserContext(app, user).packageManager
                    .getApplicationInfo(packageName, 0).targetSdkVersion >= Build.VERSION_CODES.M
            } catch (e: PackageManager.NameNotFoundException) {
                true
            }
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isHealthPermissionGroup(permGroupName: String): Boolean {
        return SdkLevel.isAtLeastU() && HEALTH_PERMISSION_GROUP.equals(permGroupName)
    }

    fun handleHealthConnectPermissions(activity: Activity) {
        if (activityResultCallback == null) {
            activityResultCallback = Consumer {
                permGroupsToSkip.add(HEALTH_PERMISSION_GROUP)
                requestInfosLiveData.update()
            }
            val healthPermissions = unfilteredAffectedPermissions.filter { permission ->
                isHealthPermission(activity, permission)
            }.toTypedArray()
            val intent: Intent = Intent(ACTION_REQUEST_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, healthPermissions)
                .putExtra(Intent.EXTRA_USER, Process.myUserHandle())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivityForResult(intent, APP_PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Send the user directly to the AppPermissionFragment. Used for R+ apps.
     *
     * @param activity The current activity
     * @param groupName The name of the permission group whose fragment should be opened
     */
    fun sendDirectlyToSettings(activity: Activity, groupName: String) {
        if (activityResultCallback == null) {
            activityResultCallback = Consumer { data ->
                if (data?.getStringExtra(EXTRA_RESULT_PERMISSION_INTERACTED) == null) {
                    // User didn't interact, count against rate limit
                    val group = groupStates[groupName to false]?.group
                        ?: groupStates[groupName to true]?.group ?: return@Consumer
                    if (group.background.isUserSet) {
                        KotlinUtils.setGroupFlags(app, group, FLAG_PERMISSION_USER_FIXED to true,
                            filterPermissions = group.backgroundPermNames)
                    } else {
                        KotlinUtils.setGroupFlags(app, group, FLAG_PERMISSION_USER_SET to true,
                            filterPermissions = group.backgroundPermNames)
                    }
                }

                permGroupsToSkip.add(groupName)
                // Update our liveData now that there is a new skipped group
                requestInfosLiveData.update()
            }
            startAppPermissionFragment(activity, groupName)
        }
    }

    fun openPhotoPicker(activity: Activity, result: Int) {
        if (activityResultCallback != null) {
            return
        }
        if (groupStates[READ_MEDIA_VISUAL to false]?.affectedPermissions == null) {
            return
        }
        activityResultCallback = Consumer { data ->
            val anySelected = data?.getBooleanExtra(INTENT_PHOTOS_SELECTED, true) == true
            if (anySelected) {
                onPermissionGrantResult(READ_MEDIA_VISUAL, null, result)
            } else {
                onPermissionGrantResult(READ_MEDIA_VISUAL, null, CANCELED)
            }
            requestInfosLiveData.update()
        }
        // A clone profile doesn't have a MediaProvider. If this user is a clone profile, open
        // the photo picker in the parent profile
        val userManager = activity.getSystemService(UserManager::class.java)!!
        val user = if (userManager.isCloneProfile) {
            userManager.getProfileParent(Process.myUserHandle()) ?: Process.myUserHandle()
        } else {
            Process.myUserHandle()
        }
        activity.startActivityForResultAsUser(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
            .putExtra(Intent.EXTRA_UID, packageInfo.uid)
            .setType(KotlinUtils.getMimeTypeForPermissions(unfilteredAffectedPermissions)),
            PHOTO_PICKER_REQUEST_CODE, user)
    }

    /**
     * Send the user to the AppPermissionFragment from a link. Used for Q- apps
     *
     * @param activity The current activity
     * @param groupName The name of the permission group whose fragment should be opened
     */
    fun sendToSettingsFromLink(activity: Activity, groupName: String) {
        startAppPermissionFragment(activity, groupName)
        activityResultCallback = Consumer { data ->
            val returnGroupName = data?.getStringExtra(EXTRA_RESULT_PERMISSION_INTERACTED)
            if (returnGroupName != null) {
                permGroupsToSkip.add(returnGroupName)
                val result = data.getIntExtra(EXTRA_RESULT_PERMISSION_RESULT, -1)
                logSettingsInteraction(returnGroupName, result)
                requestInfosLiveData.update()
            }
        }
    }

    /**
    * Shows the Permission Rationale Dialog. For use with U+ only, otherwise no-op.
    *
    * @param activity The current activity
    * @param groupName The name of the permission group whose fragment should be opened
    */
    fun showPermissionRationaleActivity(activity: Activity, groupName: String) {
        if (!SdkLevel.isAtLeastU()) {
            return
        }

        val intent = Intent(activity, PermissionRationaleActivity::class.java).apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, groupName)
            putExtra(Constants.EXTRA_SESSION_ID, sessionId)
        }
        activityResultCallback = Consumer { data ->
            val returnGroupName = data?.getStringExtra(EXTRA_RESULT_PERMISSION_INTERACTED)
            if (returnGroupName != null) {
                permGroupsToSkip.add(returnGroupName)
                val result = data.getIntExtra(EXTRA_RESULT_PERMISSION_RESULT, CANCELED)
                logSettingsInteraction(returnGroupName, result)
                requestInfosLiveData.update()
            }
        }
        activity.startActivityForResult(intent, APP_PERMISSION_REQUEST_CODE)
    }

    private fun startAppPermissionFragment(activity: Activity, groupName: String) {
        val intent = Intent(Intent.ACTION_MANAGE_APP_PERMISSION)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(Intent.EXTRA_PERMISSION_GROUP_NAME, groupName)
            .putExtra(Intent.EXTRA_USER, user)
            .putExtra(ManagePermissionsActivity.EXTRA_CALLER_NAME,
                GrantPermissionsActivity::class.java.name)
            .putExtra(Constants.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivityForResult(intent, APP_PERMISSION_REQUEST_CODE)
    }

    private fun getInstanceStateKey(groupName: String, isBackground: Boolean): String {
        return "${this::class.java.name}_${groupName}_$isBackground"
    }

    private fun logSettingsInteraction(groupName: String, result: Int) {
        val foregroundGroupState = groupStates[groupName to false]
        val backgroundGroupState = groupStates[groupName to true]
        val deniedPrejudiceInSettings =
            PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_WITH_PREJUDICE_IN_SETTINGS
        when (result) {
            GRANTED_ALWAYS -> {
                if (foregroundGroupState != null) {
                    reportRequestResult(foregroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_IN_SETTINGS)
                }
                if (backgroundGroupState != null) {
                    reportRequestResult(backgroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_IN_SETTINGS)
                }
            }
            GRANTED_FOREGROUND_ONLY -> {
                if (foregroundGroupState != null) {
                    reportRequestResult(foregroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_GRANTED_IN_SETTINGS)
                }
                if (backgroundGroupState != null) {
                    reportRequestResult(backgroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_IN_SETTINGS)
                }
            }
            DENIED -> {
                if (foregroundGroupState != null) {
                    reportRequestResult(foregroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_IN_SETTINGS)
                }
                if (backgroundGroupState != null) {
                    reportRequestResult(backgroundGroupState.affectedPermissions,
                        PERMISSION_GRANT_REQUEST_RESULT_REPORTED__RESULT__USER_DENIED_IN_SETTINGS)
                }
            }
            DENIED_DO_NOT_ASK_AGAIN -> {
                if (foregroundGroupState != null) {
                    reportRequestResult(foregroundGroupState.affectedPermissions,
                        deniedPrejudiceInSettings)
                }
                if (backgroundGroupState != null) {
                    reportRequestResult(backgroundGroupState.affectedPermissions,
                        deniedPrejudiceInSettings)
                }
            }
        }
    }

    /**
     * Log all permission groups which were requested
     */
    fun logRequestedPermissionGroups() {
        if (groupStates.isEmpty()) {
            return
        }
        val groups = groupStates.map { it.value.group }
        SafetyNetLogger.logPermissionsRequested(packageName, packageInfo.uid, groups)
    }

    /**
     * Log information about the buttons which were shown and clicked by the user.
     *
     * @param groupName The name of the permission group which was interacted with
     * @param selectedPrecision Selected precision of the location permission - bit flags indicate
     *                          which locations were chosen
     * @param clickedButton The button that was clicked by the user
     * @param presentedButtons All buttons which were shown to the user
     */
    fun logClickedButtons(
        groupName: String?,
        selectedPrecision: Int,
        clickedButton: Int,
        presentedButtons: Int,
        isPermissionRationaleShown: Boolean
    ) {
        if (groupName == null) {
            return
        }

        if (!requestInfosLiveData.isInitialized || !packageInfoLiveData.isInitialized) {
            Log.wtf(LOG_TAG, "Logged buttons presented and clicked permissionGroupName=" +
                    "$groupName package=$packageName presentedButtons=$presentedButtons " +
                    "clickedButton=$clickedButton isPermissionRationaleShown=" +
                    "$isPermissionRationaleShown sessionId=$sessionId, but requests were not yet" +
                    "initialized", IllegalStateException())
            return
        }

        PermissionControllerStatsLog.write(GRANT_PERMISSIONS_ACTIVITY_BUTTON_ACTIONS,
                groupName, packageInfo.uid, packageName, presentedButtons, clickedButton, sessionId,
                packageInfo.targetSdkVersion, selectedPrecision,
                isPermissionRationaleShown)
        Log.v(LOG_TAG, "Logged buttons presented and clicked permissionGroupName=" +
                "$groupName uid=${packageInfo.uid} selectedPrecision=$selectedPrecision " +
                "package=$packageName presentedButtons=$presentedButtons " +
                "clickedButton=$clickedButton isPermissionRationaleShown=" +
                "$isPermissionRationaleShown sessionId=$sessionId " +
                "targetSdk=${packageInfo.targetSdkVersion}")
    }

    /**
     * Use the autoGrantNotifier to notify of auto-granted permissions.
     */
    fun autoGrantNotify() {
        autoGrantNotifier?.notifyOfAutoGrantPermissions(true)
    }

    companion object {
        const val APP_PERMISSION_REQUEST_CODE = 1
        const val PHOTO_PICKER_REQUEST_CODE = 2
        private const val STATE_UNKNOWN = 0
        private const val STATE_ALLOWED = 1
        private const val STATE_DENIED = 2
        private const val STATE_SKIPPED = 3
        private const val STATE_ALREADY_ALLOWED = 4

        /**
         * An enum that represents the type of message which should be shown- foreground,
         * background, upgrade, or no message.
         */
        enum class RequestMessage {
            FG_MESSAGE,
            BG_MESSAGE,
            UPGRADE_MESSAGE,
            NO_MESSAGE,
            FG_FINE_LOCATION_MESSAGE,
            FG_COARSE_LOCATION_MESSAGE,
            STORAGE_SUPERGROUP_MESSAGE_Q_TO_S,
            STORAGE_SUPERGROUP_MESSAGE_PRE_Q,
            MORE_PHOTOS_MESSAGE,
        }

        /**
         * Make a copy of a list of permissions that is filtered to remove permissions blocked
         * according to the target SDK level.
         */
        fun getSanitizedPermissionsList(
            permissions: Array<String?>,
            targetSdkVersion: Int
        ): List<String> {
            return permissions
                .filter { !it.isNullOrEmpty() }
                // POST_NOTIFICATIONS is actively disallowed to be declared by apps below T.
                // Others we don't care as much if they were declared but not used.
                .filter { targetSdkVersion >= Build.VERSION_CODES.TIRAMISU ||
                    it != POST_NOTIFICATIONS }
                .filterIsInstance<String>()
        }
    }
}

/**
 * Factory for an AppPermissionViewModel
 *
 * @param app The current application
 * @param packageName The name of the package this ViewModel represents
 */
class GrantPermissionsViewModelFactory(
    private val app: Application,
    private val packageName: String,
    private val requestedPermissions: List<String>,
    private val sessionId: Long,
    private val savedState: Bundle?
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return GrantPermissionsViewModel(app, packageName, requestedPermissions,
            sessionId, savedState) as T
    }
}
