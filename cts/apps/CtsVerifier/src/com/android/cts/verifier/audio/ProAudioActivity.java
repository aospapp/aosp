/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;  // needed to access resource in CTSVerifier project namespace.

import java.util.List;

public class ProAudioActivity
        extends PassFailButtons.Activity
        implements View.OnClickListener {
    private static final String TAG = ProAudioActivity.class.getName();
    private static final boolean DEBUG = false;

    // Flags
    private boolean mClaimsLowLatencyAudio;    // CDD ProAudio section C-1-1
    private boolean mClaimsMIDI;               // CDD ProAudio section C-1-4
    private boolean mClaimsUSBHostMode;        // CDD ProAudio section C-1-3
    private boolean mClaimsUSBPeripheralMode;  // CDD ProAudio section C-1-3
    private boolean mClaimsHDMI;               // CDD ProAudio section C-1-3

    // Values
    private static final double LATENCY_MS_LIMIT = 20.0; // CDD ProAudio section C-1-2
    private double mRoundTripLatency;
    private static final double CONFIDENCE_LIMIT = 0.75; // TBD
    private double mRoundTripConfidence;

    // Peripheral(s)
    AudioManager mAudioManager;
    private boolean mIsPeripheralAttached;  // CDD ProAudio section C-1-3
    private AudioDeviceInfo mOutputDevInfo;
    private AudioDeviceInfo mInputDevInfo;

    private AudioDeviceInfo mHDMIDeviceInfo;

    // Widgets
    TextView mInputDeviceTxt;
    TextView mOutputDeviceTxt;
    TextView mRoundTripLatencyTxt;
    TextView mRoundTripConfidenceTxt;
    TextView mHDMISupportLbl;

    CheckBox mClaimsHDMICheckBox;

    public ProAudioActivity() {
        super();
    }

    private boolean claimsLowLatencyAudio() {
        // CDD Section C-1-1: android.hardware.audio.low_latency
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
    }

    private boolean claimsMIDI() {
        // CDD Section C-1-4: android.software.midi
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI);
    }

    private boolean claimsUSBHostMode() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
    }

    private boolean claimsUSBPeripheralMode() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY);
    }

    private void showConnectedAudioPeripheral() {
        mInputDeviceTxt.setText(
                mInputDevInfo != null ? mInputDevInfo.getProductName().toString()
                        : "");
        mOutputDeviceTxt.setText(
                mOutputDevInfo != null ? mOutputDevInfo.getProductName().toString()
                        : "");
    }

    // HDMI Stuff
    private boolean isHDMIValid() {
        if (mHDMIDeviceInfo == null) {
            return false;
        }

        // MUST support output in stereo and eight channels...
        boolean has2Chans = false;
        boolean has8Chans = false;
        int[] channelCounts = mHDMIDeviceInfo.getChannelCounts();
        for (int count : channelCounts) {
            if (count == 2) {
                has2Chans = true;
            } else if (count == 8) {
                has8Chans = true;
            }
        }
        if (!has2Chans || !has8Chans) {
            return false;
        }

        // at 20-bit or 24-bit depth
        boolean hasFloatEncoding = false;
        int[] encodings = mHDMIDeviceInfo.getEncodings();
        for (int encoding : encodings) {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                hasFloatEncoding = true;
                break;
            }
        }
        if (!hasFloatEncoding) {
            return false;
        }

         // and 192 kHz
        boolean has192K = false;
        int[] sampleRates = mHDMIDeviceInfo.getSampleRates();
        for (int rate : sampleRates) {
            if (rate >= 192000) {
                has192K = true;
            }
        }
        if (!has192K) {
            return false;
        }

        // without bit-depth loss or resampling (hmmmmm....).

        return true;
    }

    private void calculatePass() {
        boolean hasPassed =
                mClaimsLowLatencyAudio && mClaimsMIDI &&
                mClaimsUSBHostMode && mClaimsUSBPeripheralMode &&
                (!mClaimsHDMI || isHDMIValid()) &&
                mOutputDevInfo != null && mInputDevInfo != null &&
                mRoundTripLatency != 0.0 && mRoundTripLatency <= LATENCY_MS_LIMIT &&
                mRoundTripConfidence >= CONFIDENCE_LIMIT;
        getPassButton().setEnabled(hasPassed);
    }

    //
    // Loopback App Stuff
    //
    private final static String LOOPBACK_PACKAGE_NAME = "org.drrickorang.loopback";

    // Test Intents
    // From Loopback App LoobackActivity.java
    private static final String INTENT_TEST_TYPE = "TestType";

    // from Loopback App Constant.java
    public static final int LOOPBACK_PLUG_AUDIO_THREAD_TEST_TYPE_LATENCY = 222;

    public boolean isLoopbackAppInstalled() {
        try {
            getPackageManager().getPackageInfo(
                    LOOPBACK_PACKAGE_NAME, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // This indicates that the specified app (Loopback in this case) is NOT installed
            // fall through...
        }
        return false;
    }

    // arbitrary request code
    private static final int LATENCY_RESULTS_REQUEST_CODE = 1;
    private static final String KEY_CTSINVOCATION = "CTS-Test";
    private static final String KEY_ROUND_TRIP_TIME = "RoundTripTime";
    private static final String KEY_ROUND_TRIP_CONFIDENCE = "Confidence";

    // We may need to iterate and average round-trip measurements
    // So add this plumbing though NOT USED.
    private static final String KEY_NUMITERATIONS = "NumIterations";
    private static final int NUM_ROUNDTRIPITERATIONS = 3;

    private void runRoundTripTest() {
        if (!isLoopbackAppInstalled()) {
            Toast.makeText(this, "Loopback App not installed", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mIsPeripheralAttached) {
            Toast.makeText(this, "Please connect a USB audio peripheral with loopback cables" +
                    " before running the latency test.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mRoundTripLatency = 0.0;
        mRoundTripConfidence = 0.0;
        Intent intent = new Intent(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(
            new ComponentName(LOOPBACK_PACKAGE_NAME,LOOPBACK_PACKAGE_NAME + ".LoopbackActivity"));

        intent.putExtra(KEY_CTSINVOCATION, "CTS-Verifier Invocation");
        intent.putExtra(INTENT_TEST_TYPE, LOOPBACK_PLUG_AUDIO_THREAD_TEST_TYPE_LATENCY);
        intent.putExtra(KEY_NUMITERATIONS, NUM_ROUNDTRIPITERATIONS);

        startActivityForResult(intent, LATENCY_RESULTS_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (resultCode == RESULT_OK) {
            Toast.makeText(this, "Round Trip Test Complete.", Toast.LENGTH_SHORT).show();
            if (requestCode == LATENCY_RESULTS_REQUEST_CODE) {
                Bundle extras = data != null ? data.getExtras() : null;
                if (extras != null) {
                    mRoundTripLatency =  extras.getDouble(KEY_ROUND_TRIP_TIME);
                    mRoundTripLatencyTxt.setText(String.format("%.2f ms", mRoundTripLatency));
                    mRoundTripConfidence = extras.getDouble(KEY_ROUND_TRIP_CONFIDENCE);
                    mRoundTripConfidenceTxt.setText(String.format("%.2f", mRoundTripConfidence));
                }
            }
            calculatePass();
        } else {
            Toast.makeText(this, "Round Trip Test Canceled.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.pro_audio);

        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        mAudioManager.registerAudioDeviceCallback(new ConnectListener(), new Handler());

        setPassFailButtonClickListeners();
        setInfoResources(R.string.proaudio_test, R.string.proaudio_info, -1);

        mClaimsLowLatencyAudio = claimsLowLatencyAudio();
        ((TextView)findViewById(R.id.proAudioHasLLALbl)).setText("" + mClaimsLowLatencyAudio);

        mClaimsMIDI = claimsMIDI();
        ((TextView)findViewById(R.id.proAudioHasMIDILbl)).setText("" + mClaimsMIDI);

        mClaimsUSBHostMode = claimsUSBHostMode();
        ((TextView)findViewById(R.id.proAudioMidiHasUSBHostLbl)).setText("" + mClaimsUSBHostMode);

        mClaimsUSBPeripheralMode = claimsUSBPeripheralMode();
        ((TextView)findViewById(
                R.id.proAudioMidiHasUSBPeripheralLbl)).setText("" + mClaimsUSBPeripheralMode);

        // Connected Device
        mInputDeviceTxt = ((TextView)findViewById(R.id.proAudioInputLbl));
        mOutputDeviceTxt = ((TextView)findViewById(R.id.proAudioOutputLbl));

        // Round-trip Latency
        mRoundTripLatencyTxt = (TextView)findViewById(R.id.proAudioRoundTripLbl);
        mRoundTripConfidenceTxt = (TextView)findViewById(R.id.proAudioConfidenceLbl);
        ((Button)findViewById(R.id.proAudio_runRoundtripBtn)).setOnClickListener(this);

        // HDMI
        mHDMISupportLbl = (TextView)findViewById(R.id.proAudioHDMISupportLbl);
        mClaimsHDMICheckBox = (CheckBox)findViewById(R.id.proAudioHasHDMICheckBox);
        mClaimsHDMICheckBox.setOnClickListener(this);

        calculatePass();
    }

    private void scanPeripheralList(AudioDeviceInfo[] devices) {
        // CDD Section C-1-3: USB port, host-mode support

        // Can't just use the first record because then we will only get
        // Source OR sink, not both even on devices that are both.
        mOutputDevInfo = null;
        mInputDevInfo = null;

        // Any valid peripherals
        // Do we leave in the Headset test to support a USB-Dongle?
        for (AudioDeviceInfo devInfo : devices) {
            if (devInfo.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||     // USB Peripheral
                devInfo.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ||    // USB dongle+LBPlug
                devInfo.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || // Loopback Plug?
                devInfo.getType() == AudioDeviceInfo.TYPE_AUX_LINE) { // Aux-cable loopback?
                if (devInfo.isSink()) {
                    mOutputDevInfo = devInfo;
                }
                if (devInfo.isSource()) {
                    mInputDevInfo = devInfo;
                }
            } else if (devInfo.isSink() && devInfo.getType() == AudioDeviceInfo.TYPE_HDMI) {
                mHDMIDeviceInfo = devInfo;
            }
        }

        mIsPeripheralAttached = mOutputDevInfo != null || mInputDevInfo != null;
        showConnectedAudioPeripheral();

        if (mHDMIDeviceInfo != null) {
            mClaimsHDMICheckBox.setChecked(true);
            mHDMISupportLbl.setText(getResources().getString(
                    isHDMIValid() ? R.string.pass_button_text : R.string.fail_button_text));
        }
        mHDMISupportLbl.setText(getResources().getString(R.string.audio_proaudio_NA));

        calculatePass();
    }

    private class ConnectListener extends AudioDeviceCallback {
        /*package*/ ConnectListener() {}

        //
        // AudioDevicesManager.OnDeviceConnectionListener
        //
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            scanPeripheralList(mAudioManager.getDevices(AudioManager.GET_DEVICES_ALL));
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            scanPeripheralList(mAudioManager.getDevices(AudioManager.GET_DEVICES_ALL));
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
        case R.id.proAudio_runRoundtripBtn:
            runRoundTripTest();
            break;

        case R.id.proAudioHasHDMICheckBox:
            if (mClaimsHDMICheckBox.isChecked()) {
                AlertDialog.Builder builder =
                        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
                builder.setTitle(getResources().getString(R.string.proaudio_hdmi_infotitle));
                builder.setMessage(getResources().getString(R.string.proaudio_hdmi_message));
                builder.setPositiveButton(android.R.string.yes,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {}
                 });
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.show();

                mClaimsHDMI = true;
                mHDMISupportLbl.setText(getResources().getString(R.string.audio_proaudio_pending));
            } else {
                mClaimsHDMI = false;
                mHDMISupportLbl.setText(getResources().getString(R.string.audio_proaudio_NA));
            }
            break;
        }
    }
}
