/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.healthconnect.controller.shared.preference

import android.content.Context
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.healthconnect.controller.R

class BannerPreference constructor(context: Context) : Preference(context) {
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerMessage: TextView
    private lateinit var bannerButton: Button

    private var dismissButton: ImageView? = null

    private var buttonText: String? = null
    private var buttonAction: OnClickListener? = null
    private var isDismissable = false
    private var dismissAction: OnClickListener? = null

    init {
        layoutResource = R.layout.widget_banner_preference
        isSelectable = false
    }

    fun setButton(buttonText: String) {
        this.buttonText = buttonText
    }

    fun setButtonOnClickListener(onClickListener: OnClickListener?) {
        this.buttonAction = onClickListener
    }

    fun setIsDismissable(isDismissable: Boolean) {
        this.isDismissable = isDismissable
    }

    fun setDismissAction(onClickListener: OnClickListener?) {
        this.dismissAction = onClickListener
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bannerIcon = holder.findViewById(R.id.banner_icon) as ImageView
        bannerTitle = holder.findViewById(R.id.banner_title) as TextView
        bannerMessage = holder.findViewById(R.id.banner_message) as TextView
        bannerButton = holder.findViewById(R.id.banner_button) as Button

        bannerTitle.text = title
        bannerMessage.text = summary
        bannerIcon.background = icon

        // set button text and visibility
        buttonText?.let {
            bannerButton.text = it
            bannerButton.visibility = View.VISIBLE
            bannerButton.setOnClickListener(buttonAction)
        }

        if (isDismissable) {
            dismissButton = holder.findViewById(R.id.dismiss_button) as ImageView
            dismissButton?.visibility = View.VISIBLE
            dismissButton?.setOnClickListener(dismissAction)
        }
    }
}
