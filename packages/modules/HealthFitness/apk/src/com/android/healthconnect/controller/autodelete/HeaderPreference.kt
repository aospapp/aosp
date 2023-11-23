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
package com.android.healthconnect.controller.autodelete

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.utils.DeviceInfoUtilsImpl
import com.android.healthconnect.controller.utils.convertTextViewIntoLink

/** Custom preference for the header of the auto-delete screen. */
class HeaderPreference constructor(context: Context, private val activity: FragmentActivity) :
    Preference(context) {

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)

        val widgetFrame: ViewGroup = holder?.findViewById(android.R.id.widget_frame) as ViewGroup
        val widgetFrameParent: LinearLayout = widgetFrame.parent as LinearLayout
        val iconFrame: LinearLayout? = holder.findViewById(android.R.id.icon_frame) as LinearLayout?
        widgetFrameParent.removeView(iconFrame)

        var headerView: LinearLayout? =
            holder.findViewById(R.id.auto_delete_header_content) as LinearLayout?
        if (headerView == null) {
            val inflater: LayoutInflater = context.getSystemService(LayoutInflater::class.java)
            headerView =
                inflater.inflate(R.layout.widget_auto_delete_header, widgetFrameParent, false)
                    as LinearLayout?
            widgetFrameParent.addView(headerView, 0)
        }

        val linkView: TextView? = headerView?.findViewById(R.id.link) as TextView?
        val linkString: String = context.getString(R.string.auto_delete_learn_more)
        linkView?.let {
            convertTextViewIntoLink(it, linkString, 0, linkString.length) {
                DeviceInfoUtilsImpl().openHCGetStartedLink(activity)
            }
        }
    }
}
