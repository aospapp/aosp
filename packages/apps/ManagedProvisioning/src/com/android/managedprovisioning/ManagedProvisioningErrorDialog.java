/*
 * Copyright 2014, The Android Open Source Project
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

package com.android.managedprovisioning;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

/**
 * Dialog used to notify the user that the managed provisioning failed, and shutdown the current
 * activity.
 *
 * Note: You should not do any more work in your app after showing this dialog. See guidelines for
 * {@code Activity#finish()} method call.
 */
public class ManagedProvisioningErrorDialog extends DialogFragment {

  private final String message;

  public ManagedProvisioningErrorDialog(String message) {
      super();
      this.message = message;
  }

  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
      // TODO: This disappears when you rotate, fix it when we refactor the app

      AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getActivity());
      // TODO: Get strings from PM.
      alertDialogBuilder.setTitle(R.string.provisioning_error_title)
              .setMessage(message)
              .setCancelable(false)
              .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // Close activity
                            getActivity().setResult(Activity.RESULT_CANCELED);
                            getActivity().finish();
                      }});
      return alertDialogBuilder.create();
  }
}
