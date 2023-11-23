/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.bluetooth.audio_util;

import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.android.bluetooth.avrcp.AvrcpTargetService;

/**
 * Manager class for player apps.
 */
public class PlayerSettingsManager {
    private static final String TAG = "PlayerSettingsManager";

    private final MediaPlayerList mMediaPlayerList;
    private final AvrcpTargetService mService;

    private MediaControllerCompat mActivePlayerController;
    private final MediaControllerCallback mControllerCallback;

    /**
     * Instantiates a new PlayerSettingsManager.
     *
     * @param mediaPlayerList is used to retrieve the current active player.
     */
    public PlayerSettingsManager(MediaPlayerList mediaPlayerList, AvrcpTargetService service) {
        mService = service;
        mMediaPlayerList = mediaPlayerList;
        mMediaPlayerList.setPlayerSettingsCallback(
                (mediaPlayerWrapper) -> activePlayerChanged(mediaPlayerWrapper));
        mControllerCallback = new MediaControllerCallback();

        MediaPlayerWrapper wrapper = mMediaPlayerList.getActivePlayer();
        if (wrapper != null) {
            mActivePlayerController = new MediaControllerCompat(mService,
                    MediaSessionCompat.Token.fromToken(wrapper.getSessionToken()));
            mActivePlayerController.registerCallback(mControllerCallback);
        } else {
            mActivePlayerController = null;
        }
    }

    /**
     * Unregister callbacks
     */
    public void cleanup() {
        updateRemoteDevice();
        if (mActivePlayerController != null) {
            mActivePlayerController.unregisterCallback(mControllerCallback);
        }
    }

    /**
     * Updates the active player controller.
     */
    private void activePlayerChanged(MediaPlayerWrapper mediaPlayerWrapper) {
        if (mActivePlayerController != null) {
            mActivePlayerController.unregisterCallback(mControllerCallback);
        }
        if (mediaPlayerWrapper != null) {
            mActivePlayerController = new MediaControllerCompat(mService,
                    MediaSessionCompat.Token.fromToken(mediaPlayerWrapper.getSessionToken()));
            mActivePlayerController.registerCallback(mControllerCallback);
        } else {
            mActivePlayerController = null;
            updateRemoteDevice();
        }
    }

    /**
     * Sends the MediaController values of the active player to the remote device.
     *
     * This is called when:
     * - The class is created and the session is ready
     * - The class is destroyed
     * - The active player changed and the session is ready
     * - The last active player has been removed
     * - The repeat / shuffle player state changed
     */
    private void updateRemoteDevice() {
        mService.sendPlayerSettings(getPlayerRepeatMode(), getPlayerShuffleMode());
    }

    /**
     * Called from remote device to set the active player repeat mode.
     */
    public boolean setPlayerRepeatMode(int repeatMode) {
        if (mActivePlayerController == null) {
            return false;
        }
        MediaControllerCompat.TransportControls controls =
                mActivePlayerController.getTransportControls();
        switch (repeatMode) {
            case PlayerSettingsValues.STATE_REPEAT_OFF:
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
                return true;
            case PlayerSettingsValues.STATE_REPEAT_SINGLE_TRACK:
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ONE);
                return true;
            case PlayerSettingsValues.STATE_REPEAT_GROUP:
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_GROUP);
                return true;
            case PlayerSettingsValues.STATE_REPEAT_ALL_TRACK:
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ALL);
                return true;
            default:
                controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
                return false;
        }
    }

    /**
     * Called from remote device to set the active player shuffle mode.
     */
    public boolean setPlayerShuffleMode(int shuffleMode) {
        if (mActivePlayerController == null) {
            return false;
        }
        MediaControllerCompat.TransportControls controls =
                mActivePlayerController.getTransportControls();
        switch (shuffleMode) {
            case PlayerSettingsValues.STATE_SHUFFLE_OFF:
                controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
                return true;
            case PlayerSettingsValues.STATE_SHUFFLE_GROUP:
                controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_GROUP);
                return true;
            case PlayerSettingsValues.STATE_SHUFFLE_ALL_TRACK:
                controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL);
                return true;
            default:
                controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
                return false;
        }
    }

    /**
     * Retrieves & converts the repeat value of the active player MediaController to AVRCP values
     */
    public int getPlayerRepeatMode() {
        if (mActivePlayerController == null) {
            return PlayerSettingsValues.STATE_REPEAT_OFF;
        }
        int mediaFwkMode = mActivePlayerController.getRepeatMode();
        switch (mediaFwkMode) {
            case PlaybackStateCompat.REPEAT_MODE_NONE:
                return PlayerSettingsValues.STATE_REPEAT_OFF;
            case PlaybackStateCompat.REPEAT_MODE_ONE:
                return PlayerSettingsValues.STATE_REPEAT_SINGLE_TRACK;
            case PlaybackStateCompat.REPEAT_MODE_GROUP:
                return PlayerSettingsValues.STATE_REPEAT_GROUP;
            case PlaybackStateCompat.REPEAT_MODE_ALL:
                return PlayerSettingsValues.STATE_REPEAT_ALL_TRACK;
            case PlaybackStateCompat.REPEAT_MODE_INVALID:
                return PlayerSettingsValues.STATE_REPEAT_OFF;
            default:
                return PlayerSettingsValues.STATE_REPEAT_OFF;
        }
    }

    /**
     * Retrieves & converts the shuffle value of the active player MediaController to AVRCP values
     */
    public int getPlayerShuffleMode() {
        if (mActivePlayerController == null) {
            return PlayerSettingsValues.STATE_SHUFFLE_OFF;
        }
        int mediaFwkMode = mActivePlayerController.getShuffleMode();
        switch (mediaFwkMode) {
            case PlaybackStateCompat.SHUFFLE_MODE_NONE:
                return PlayerSettingsValues.STATE_SHUFFLE_OFF;
            case PlaybackStateCompat.SHUFFLE_MODE_GROUP:
                return PlayerSettingsValues.STATE_SHUFFLE_GROUP;
            case PlaybackStateCompat.SHUFFLE_MODE_ALL:
                return PlayerSettingsValues.STATE_SHUFFLE_ALL_TRACK;
            case PlaybackStateCompat.SHUFFLE_MODE_INVALID:
                return PlayerSettingsValues.STATE_SHUFFLE_OFF;
            default:
                return PlayerSettingsValues.STATE_SHUFFLE_OFF;
        }
    }

    // Receives callbacks from the MediaControllerCompat.
    private class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onRepeatModeChanged(final int repeatMode) {
            updateRemoteDevice();
        }

        @Override
        public void onSessionReady() {
            updateRemoteDevice();
        }

        @Override
        public void onShuffleModeChanged(final int shuffleMode) {
            updateRemoteDevice();
        }
    }

    /**
     * Class containing all the Shuffle/Repeat values as defined in the BT spec.
     */
    public static final class PlayerSettingsValues {
        /**
         * Repeat setting, as defined by Bluetooth specification.
         */
        public static final int SETTING_REPEAT = 2;

        /**
         * Shuffle setting, as defined by Bluetooth specification.
         */
        public static final int SETTING_SHUFFLE = 3;

        /**
         * Repeat OFF state, as defined by Bluetooth specification.
         */
        public static final int STATE_REPEAT_OFF = 1;

        /**
         * Single track repeat, as defined by Bluetooth specification.
         */
        public static final int STATE_REPEAT_SINGLE_TRACK = 2;

        /**
         * All track repeat, as defined by Bluetooth specification.
         */
        public static final int STATE_REPEAT_ALL_TRACK = 3;

        /**
         * Group repeat, as defined by Bluetooth specification.
         */
        public static final int STATE_REPEAT_GROUP = 4;

        /**
         * Shuffle OFF state, as defined by Bluetooth specification.
         */
        public static final int STATE_SHUFFLE_OFF = 1;

        /**
         * All track shuffle, as defined by Bluetooth specification.
         */
        public static final int STATE_SHUFFLE_ALL_TRACK = 2;

        /**
         * Group shuffle, as defined by Bluetooth specification.
         */
        public static final int STATE_SHUFFLE_GROUP = 3;

        /**
         * Default state off.
         */
        public static final int STATE_DEFAULT_OFF = 1;
    }
}
