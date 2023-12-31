/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.cts.verifier.audio;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;

import com.android.cts.verifier.audio.peripheralprofile.PeripheralProfile;
import com.android.cts.verifier.audio.peripheralprofile.ProfileButtonAttributes;

import com.android.cts.verifier.R;  // needed to access resource in CTSVerifier project namespace.

public class USBAudioPeripheralButtonsActivity extends USBAudioPeripheralActivity {
    private static final String TAG = "USBAudioPeripheralButtonsActivity";

    // State
    private boolean mHasBtnA;
    private boolean mHasBtnB;
    private boolean mHasBtnC;

    // Widgets
    private TextView mBtnALabelTxt;
    private TextView mBtnBLabelTxt;
    private TextView mBtnCLabelTxt;

    private TextView mBtnAStatusTxt;
    private TextView mBtnBStatusTxt;
    private TextView mBtnCStatusTxt;

    public USBAudioPeripheralButtonsActivity() {
        super(false); // Mandated peripheral is NOT required
    }

    private void showDisableAssistantDialog() {
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle(getResources().getString(R.string.uapButtonsDisableAssistantTitle));
        builder.setMessage(getResources().getString(R.string.uapButtonsDisableAssistant));
        builder.setPositiveButton(android.R.string.ok,
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {}
         });
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uap_buttons_panel);

        connectPeripheralStatusWidgets();

        mBtnALabelTxt = (TextView)findViewById(R.id.uap_buttonsBtnALabelTx);
        mBtnBLabelTxt = (TextView)findViewById(R.id.uap_buttonsBtnBLabelTx);
        mBtnCLabelTxt = (TextView)findViewById(R.id.uap_buttonsBtnCLabelTx);

        mBtnAStatusTxt = (TextView)findViewById(R.id.uap_buttonsBtnAStatusTx);
        mBtnBStatusTxt = (TextView)findViewById(R.id.uap_buttonsBtnBStatusTx);
        mBtnCStatusTxt = (TextView)findViewById(R.id.uap_buttonsBtnCStatusTx);

        setPassFailButtonClickListeners();
        setInfoResources(R.string.usbaudio_buttons_test, R.string.usbaudio_buttons_info, -1);

        showDisableAssistantDialog();

        connectUSBPeripheralUI();
    }

    private void showButtonsState() {
        int ctrlColor = mIsPeripheralAttached ? Color.WHITE : Color.GRAY;
        mBtnALabelTxt.setTextColor(ctrlColor);
        mBtnAStatusTxt.setTextColor(ctrlColor);
        mBtnBLabelTxt.setTextColor(ctrlColor);
        mBtnBStatusTxt.setTextColor(ctrlColor);
        mBtnCLabelTxt.setTextColor(ctrlColor);
        mBtnCStatusTxt.setTextColor(ctrlColor);

        mBtnAStatusTxt.setText(getString(
            mHasBtnA ? R.string.uapButtonsRecognized : R.string.uapButtonsNotRecognized));
        mBtnBStatusTxt.setText(getString(
            mHasBtnB ? R.string.uapButtonsRecognized : R.string.uapButtonsNotRecognized));
        mBtnCStatusTxt.setText(getString(
            mHasBtnC ? R.string.uapButtonsRecognized : R.string.uapButtonsNotRecognized));

        calculateMatch();
    }

    private void calculateMatch() {
        if (mIsPeripheralAttached) {
            boolean match = mHasBtnA && mHasBtnB && mHasBtnC;
            Log.i(TAG, "match:" + match);
            getPassButton().setEnabled(match);
        } else {
            getPassButton().setEnabled(false);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Log.i(TAG, "onKeyDown(" + keyCode + ")");
        switch (keyCode) {
        // Function A control event
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            mHasBtnA = true;
            break;

        // Function B control event
        case KeyEvent.KEYCODE_VOLUME_UP:
            mHasBtnB = true;
            break;

        // Function C control event
        case KeyEvent.KEYCODE_VOLUME_DOWN:
            mHasBtnC = true;
            break;
        }

        showButtonsState();
        calculateMatch();

        return super.onKeyDown(keyCode, event);
    }

    //
    // USBAudioPeripheralActivity
    //
    public void updateConnectStatus() {
        mHasBtnA = mHasBtnB = mHasBtnC = false;
        showButtonsState();
        calculateMatch();
    }
}

