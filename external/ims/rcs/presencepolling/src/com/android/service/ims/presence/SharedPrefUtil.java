/*
 * Copyright (c) 2015, Motorola Mobility LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of Motorola Mobility nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MOTOROLA MOBILITY LLC BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package com.android.service.ims.presence;

import android.content.Context;
import android.content.SharedPreferences;

public class SharedPrefUtil {
    public static final String EAB_SHARED_PREF = "com.android.vt.eab";
    public static final String INIT_DONE = "init_done";
    private static final String CONTACT_CHANGED_PREF_KEY = "timestamp_change";
    private static final String CONTACT_DELETE_PREF_KEY = "timestamp_delete";
    private static final String CONTACT_PROFILE_CHANGED_PREF_KEY = "profile_timestamp_change";
    public static final String VIDEO_CALLING_GROUP_ID = "video_calling_group_id";
    public static final String VIDEO_CALLING_WARN_SHOW = "video_calling_warning_alert_show";
    public static final String VIDEO_CALL_OFF_WARN_SHOW = "video_call_off_alert_show";
    public static final String MOBILE_DATA_ON_WARN_SHOW = "mobile_data_on_alert_show";
    public static final String VOLTE_ON_WARN_SHOW = "volte_on_alert_show";
    public static final String TTY_OFF_WARN_SHOW = "tty_off_alert_show";
    public static final String VT_ON_WARN_SHOW = "vt_on_alert_show";

    public static boolean isInitDone(Context context) {
        SharedPreferences eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE);
        return eabPref.getBoolean(INIT_DONE, false);
    }

    public static void setInitDone(Context context, boolean initDone){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(INIT_DONE, initDone).commit();
    }

    public static long getLastContactChangedTimestamp(Context context, long time) {
        SharedPreferences pref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return pref.getLong(CONTACT_CHANGED_PREF_KEY, time);
    }

    public static void saveLastContactChangedTimestamp(Context context, long time) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putLong(CONTACT_CHANGED_PREF_KEY, time).commit();
    }

    public static long getLastProfileContactChangedTimestamp(Context context, long time) {
        SharedPreferences pref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return pref.getLong(CONTACT_PROFILE_CHANGED_PREF_KEY, time);
    }

    public static void saveLastProfileContactChangedTimestamp(Context context, long time) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putLong(CONTACT_PROFILE_CHANGED_PREF_KEY, time).commit();
    }

    public static long getLastContactDeletedTimestamp(Context context, long time) {
        SharedPreferences pref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return pref.getLong(CONTACT_DELETE_PREF_KEY, time);
    }

    public static void saveLastContactDeletedTimestamp(Context context, long time) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putLong(CONTACT_DELETE_PREF_KEY, time).commit();
    }

    public static void setVideoCallingGroupId(Context context,
            long videoCallingGroupId) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putLong(VIDEO_CALLING_GROUP_ID, videoCallingGroupId).commit();
    }

    public static long getVideoCallingGroupId(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE);
        return eabPref.getLong(VIDEO_CALLING_GROUP_ID, 0L);
    }

    public static void setVideoCallingWarnDisable(Context context,
            boolean enable) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(VIDEO_CALLING_WARN_SHOW, enable).apply();
    }

    public static boolean isVideoCallingWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE);
        return eabPref.getBoolean(VIDEO_CALLING_WARN_SHOW, false);
    }

    public static void setVideoCallOffWarnDisable(Context context, boolean enable){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(VIDEO_CALL_OFF_WARN_SHOW, enable).apply();
    }

    public static boolean isVideoCallOffWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return eabPref.getBoolean(VIDEO_CALL_OFF_WARN_SHOW, false);
    }

    public static void setMobileDataOnWarnDisable(Context context, boolean enable){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(MOBILE_DATA_ON_WARN_SHOW, enable).apply();
    }

    public static boolean isMobileDataOnWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return eabPref.getBoolean(MOBILE_DATA_ON_WARN_SHOW, false);
    }

    public static void setVolteOnWarnDisable(Context context, boolean enable){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(VOLTE_ON_WARN_SHOW, enable).apply();
    }

    public static boolean isVolteOnWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return eabPref.getBoolean(VOLTE_ON_WARN_SHOW, false);
    }

    public static void setTtyOffWarnDisable(Context context, boolean enable){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(TTY_OFF_WARN_SHOW, enable).apply();
    }

    public static boolean isTtyOffWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return eabPref.getBoolean(TTY_OFF_WARN_SHOW, false);
    }

    public static void setVTOnWarnDisable(Context context, boolean enable){
        SharedPreferences.Editor eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(VT_ON_WARN_SHOW, enable).apply();
    }

    public static boolean isVTOnWarnDisabled(Context context){
        SharedPreferences eabPref = context.getSharedPreferences(EAB_SHARED_PREF,
                Context.MODE_PRIVATE);
        return eabPref.getBoolean(VT_ON_WARN_SHOW, false);
    }

    public static void resetEABSharedPref(Context context) {
        SharedPreferences.Editor eabPref = context.getSharedPreferences(
                EAB_SHARED_PREF, Context.MODE_PRIVATE).edit();
        eabPref.putBoolean(INIT_DONE, false);
        eabPref.putLong(CONTACT_CHANGED_PREF_KEY, 0);
        eabPref.putLong(CONTACT_DELETE_PREF_KEY, 0);
        eabPref.putLong(CONTACT_PROFILE_CHANGED_PREF_KEY, 0);
        eabPref.putBoolean(VIDEO_CALLING_WARN_SHOW, false);
        eabPref.putBoolean(VIDEO_CALL_OFF_WARN_SHOW, false);
        eabPref.putBoolean(MOBILE_DATA_ON_WARN_SHOW, false);
        eabPref.putBoolean(VOLTE_ON_WARN_SHOW, false);
        eabPref.putBoolean(TTY_OFF_WARN_SHOW, false);
        eabPref.putBoolean(VT_ON_WARN_SHOW, false);
        eabPref.commit();
    }
}
