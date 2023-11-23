/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.deletion

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.deletion.DeletionConstants.CONFIRMATION_EVENT
import com.android.healthconnect.controller.shared.dialog.AlertDialogBuilder
import com.android.healthconnect.controller.utils.logging.DeletionDialogConfirmationElement
import com.android.healthconnect.controller.utils.logging.HealthConnectLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint(DialogFragment::class)
class DeletionAppDataConfirmationDialogFragment : Hilt_DeletionAppDataConfirmationDialogFragment() {

    private val viewModel: DeletionViewModel by activityViewModels()
    @Inject lateinit var logger: HealthConnectLogger

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view: View = layoutInflater.inflate(R.layout.dialog_message_with_checkbox, null)
        val message = view.findViewById(R.id.dialog_message) as TextView
        val checkBox = view.findViewById(R.id.dialog_checkbox) as CheckBox

        val appName = viewModel.deletionParameters.value?.getAppName()
        message.text = getString(R.string.confirming_question_message)
        checkBox.text = getString(R.string.confirming_question_app_remove_all_permissions, appName)
        checkBox.setOnCheckedChangeListener { _, _ ->
            logger.logInteraction(
                DeletionDialogConfirmationElement
                    .DELETION_DIALOG_CONFIRMATION_REMOVE_APP_PERMISSIONS_BUTTON)
        }

        val alertDialogBuilder =
            AlertDialogBuilder(this)
                .setLogName(
                    DeletionDialogConfirmationElement.DELETION_DIALOG_CONFIRMATION_CONTAINER)
                .setTitle(getString(R.string.confirming_question_app_data_all, appName))
                .setView(view)
                .setIcon(R.attr.deleteIcon)
                .setPositiveButton(
                    R.string.confirming_question_delete_button,
                    DeletionDialogConfirmationElement.DELETION_DIALOG_CONFIRMATION_DELETE_BUTTON) {
                        _,
                        _ ->
                        viewModel.setChosenRange(ChosenRange.DELETE_RANGE_ALL_DATA)
                        viewModel.setRemovePermissions(checkBox.isChecked)
                        setFragmentResult(CONFIRMATION_EVENT, Bundle())
                    }
                .setNegativeButton(
                    android.R.string.cancel,
                    DeletionDialogConfirmationElement.DELETION_DIALOG_CONFIRMATION_CANCEL_BUTTON) {
                        _,
                        _ ->
                    }
                .setAdditionalLogging {
                    logger.logImpression(
                        DeletionDialogConfirmationElement
                            .DELETION_DIALOG_CONFIRMATION_REMOVE_APP_PERMISSIONS_BUTTON)
                }

        return alertDialogBuilder.create()
    }

    companion object {
        const val TAG = "DeletionAppDataConfirmationDialogFragment"
    }
}
