/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.platform.helpers;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.platform.helpers.ScrollUtility.ScrollActions;
import android.platform.helpers.ScrollUtility.ScrollDirection;
import android.platform.helpers.exceptions.UnknownUiException;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;

import java.util.List;
import java.util.regex.Pattern;

/** Helper class for functional test for Mediacenter test */
public class MediaCenterHelperImpl extends AbstractStandardAppHelper implements IAutoMediaHelper {

    private MediaSessionManager mMediaSessionManager;
    private UiAutomation mUiAutomation;

    private ScrollUtility mScrollUtility;
    private ScrollActions mScrollAction;
    private BySelector mBackwardButtonSelector;
    private BySelector mForwardButtonSelector;
    private BySelector mScrollableElementSelector;
    private ScrollDirection mScrollDirection;

    public MediaCenterHelperImpl(Instrumentation instr) {
        super(instr);
        mUiAutomation = instr.getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
        mMediaSessionManager =
                (MediaSessionManager)
                        instr.getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
        mScrollUtility = ScrollUtility.getInstance(getSpectatioUiUtil());
        mScrollAction =
                ScrollActions.valueOf(
                        getActionFromConfig(AutomotiveConfigConstants.MEDIA_APP_SCROLL_ACTION));
        mBackwardButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_SCROLL_FORWARD_BUTTON);
        mForwardButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_SCROLL_BACKWARD_BUTTON);
        mScrollableElementSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_SCROLL_ELEMENT);
        mScrollDirection =
                ScrollDirection.valueOf(
                        getActionFromConfig(AutomotiveConfigConstants.MEDIA_APP_SCROLL_DIRECTION));
    }

    /** {@inheritDoc} */
    @Override
    public void exit() {
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().wait1Second();
    }

    /** {@inheritDoc} */
    @Override
    public String getLauncherName() {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    /** {@inheritDoc} */
    @Override
    public void dismissInitialDialogs() {
        // Nothing to dismiss
    }

    /** {@inheritDoc} */
    @Override
    public boolean scrollUpOnePage() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean scrollDownOnePage() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public String getPackage() {
        return getPackageFromConfig(AutomotiveConfigConstants.MEDIA_CENTER_PACKAGE);
    }

    /** {@inheritDoc} */
    public void open() {
        openMediaApp();
    }

    private void openMediaApp() {
        getSpectatioUiUtil().pressHome();
        getSpectatioUiUtil().waitForIdle();
        getSpectatioUiUtil()
                .executeShellCommand(
                        getCommandFromConfig(AutomotiveConfigConstants.MEDIA_LAUNCH_COMMAND));
    }

    /** {@inheritDoc} */
    public void playMedia() {
        if (!isPlaying()) {
            BySelector playButtonSelector =
                    getUiElementFromConfig(AutomotiveConfigConstants.PLAY_PAUSE_BUTTON);
            UiObject2 playButton = getSpectatioUiUtil().findUiObject(playButtonSelector);
            validateUiObject(playButton, AutomotiveConfigConstants.PLAY_PAUSE_BUTTON);
            getSpectatioUiUtil().clickAndWait(playButton);
            getSpectatioUiUtil().wait5Seconds();
        }
    }

    /** {@inheritDoc} */
    public void playPauseMediaFromHomeScreen() {
        BySelector playButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.PLAY_PAUSE_BUTTON_HOME_SCREEN);
        UiObject2 playButtonHomeScreen = getSpectatioUiUtil().findUiObject(playButtonSelector);
        validateUiObject(
                playButtonHomeScreen, AutomotiveConfigConstants.PLAY_PAUSE_BUTTON_HOME_SCREEN);
        getSpectatioUiUtil().clickAndWait(playButtonHomeScreen);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void pauseMedia() {
        BySelector pauseButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.PLAY_PAUSE_BUTTON);
        UiObject2 pauseButton = getSpectatioUiUtil().findUiObject(pauseButtonSelector);
        validateUiObject(pauseButton, AutomotiveConfigConstants.PLAY_PAUSE_BUTTON);
        getSpectatioUiUtil().clickAndWait(pauseButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void clickNextTrack() {
        BySelector nextTrackButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.NEXT_BUTTON);
        UiObject2 nextTrackButton = getSpectatioUiUtil().findUiObject(nextTrackButtonSelector);
        validateUiObject(nextTrackButton, AutomotiveConfigConstants.NEXT_BUTTON);
        getSpectatioUiUtil().clickAndWait(nextTrackButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void clickNextTrackFromHomeScreen() {
        BySelector nextTrackButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.NEXT_BUTTON_HOME_SCREEN);
        UiObject2 nextTrackHomeScreenButton =
                getSpectatioUiUtil().findUiObject(nextTrackButtonSelector);
        validateUiObject(
                nextTrackHomeScreenButton, AutomotiveConfigConstants.NEXT_BUTTON_HOME_SCREEN);
        getSpectatioUiUtil().clickAndWait(nextTrackHomeScreenButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void clickPreviousTrack() {
        BySelector previousTrackButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.PREVIOUS_BUTTON);
        UiObject2 previousTrackMediaCenterButton =
                getSpectatioUiUtil().findUiObject(previousTrackButtonSelector);
        validateUiObject(previousTrackMediaCenterButton, AutomotiveConfigConstants.PREVIOUS_BUTTON);
        getSpectatioUiUtil().clickAndWait(previousTrackMediaCenterButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void clickPreviousTrackFromHomeScreen() {
        BySelector previousTrackButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.PREVIOUS_BUTTON_HOME_SCREEN);
        UiObject2 previousTrackHomeScreenButton =
                getSpectatioUiUtil().findUiObject(previousTrackButtonSelector);
        validateUiObject(previousTrackHomeScreenButton, AutomotiveConfigConstants.PREVIOUS_BUTTON);
        getSpectatioUiUtil().clickAndWait(previousTrackHomeScreenButton);
        getSpectatioUiUtil().wait5Seconds();
    }

    /** {@inheritDoc} */
    public void clickShuffleAll() {
        BySelector shufflePlaylistButtonSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.SHUFFLE_BUTTON);
        UiObject2 shufflePlaylistButton =
                getSpectatioUiUtil().findUiObject(shufflePlaylistButtonSelector);
        validateUiObject(shufflePlaylistButton, AutomotiveConfigConstants.SHUFFLE_BUTTON);
        getSpectatioUiUtil().clickAndWait(shufflePlaylistButton);
        getSpectatioUiUtil().wait5Seconds();
        }

    /**
     * TODO - Keeping the empty functions for now, to avoid the compilation error in Vendor it will
     * be removed after vendor clean up (b/266449779)
     */

    /** Click the nth instance among the visible menu items */
    public void clickMenuItem(int instance) {}

    /**
     * TODO - Keeping the empty functions for now, to avoid the compilation error in Vendor it will
     * be removed after vendor clean up (b/266449779)
     */

    /** {@inheritDoc} */
    @Override
    public void openMenuWith(String... menuOptions) {}

    /**
     * TODO - Keeping the empty functions for now, to avoid the compilation error in Vendor it will
     * be removed after vendor clean up (b/266449779)
     */

    /** {@inheritDoc} */
    @Override
    public void openNowPlayingWith(String trackName) {}

    /** {@inheritDoc} */
    public String getMediaTrackName() {
        String track;
        BySelector mediaControlSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MINIMIZED_MEDIA_CONTROLS);
        UiObject2 mediaControl = getSpectatioUiUtil().findUiObject(mediaControlSelector);

        if (mediaControl != null) {
            track = getMediaTrackNameFromMinimizedControl();
        } else {
            track = getMediaTrackNameFromPlayback();
        }
        return track;
    }

    /** {@inheritDoc} */
    public String getMediaTrackNameFromHomeScreen() {
        String trackName;
        BySelector trackNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.TRACK_NAME_HOME_SCREEN);
        UiObject2 trackNamexTextHomeScreen = getSpectatioUiUtil().findUiObject(trackNameSelector);
        validateUiObject(
                trackNamexTextHomeScreen, AutomotiveConfigConstants.TRACK_NAME_HOME_SCREEN);
        trackName = trackNamexTextHomeScreen.getText();
        return trackName;
    }

    private String getMediaTrackNameFromMinimizedControl() {
        String trackName;
        BySelector trackNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.TRACK_NAME_MINIMIZED_CONTROL);
        UiObject2 trackNameTextMinimizeControl =
                getSpectatioUiUtil().findUiObject(trackNameSelector);
        validateUiObject(
                trackNameTextMinimizeControl,
                AutomotiveConfigConstants.TRACK_NAME_MINIMIZED_CONTROL);
        trackName = trackNameTextMinimizeControl.getText();
        return trackName;
    }

    private String getMediaTrackNameFromPlayback() {
        String trackName;
        BySelector trackNameSelector = getUiElementFromConfig(AutomotiveConfigConstants.TRACK_NAME);
        UiObject2 trackNameTextPlayback = getSpectatioUiUtil().findUiObject(trackNameSelector);
        validateUiObject(trackNameTextPlayback, AutomotiveConfigConstants.TRACK_NAME);
        trackName = trackNameTextPlayback.getText();
        return trackName;
    }

    /** {@inheritDoc} */
    public void goBackToMediaHomePage() {
        minimizeNowPlaying();
        BySelector back_btnSelector = getUiElementFromConfig(AutomotiveConfigConstants.BACK_BUTTON);
        UiObject2 back_btn = getSpectatioUiUtil().findUiObject(back_btnSelector);
        validateUiObject(back_btn, AutomotiveConfigConstants.BACK_BUTTON);
        while (back_btn != null) {
            getSpectatioUiUtil().clickAndWait(back_btn);
            getSpectatioUiUtil().wait5Seconds();
            back_btn = getSpectatioUiUtil().findUiObject(back_btnSelector);
        }
    }

    /** Minimize the Now Playing window. */
    /** {@inheritDoc} */
    @Override
    public void minimizeNowPlaying() {
        BySelector trackNameSelector = getUiElementFromConfig(AutomotiveConfigConstants.TRACK_NAME);
        UiObject2 trackNameText = getSpectatioUiUtil().findUiObject(trackNameSelector);
        if (trackNameText != null) {
            trackNameText.swipe(Direction.DOWN, 1.0f, 500);
        }
    }

    /** Maximize the Now Playing window. */
    /** {@inheritDoc} */
    @Override
    public void maximizeNowPlaying() {
        BySelector trackNameSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MINIMIZED_MEDIA_CONTROLS);
        UiObject2 trackNameText = getSpectatioUiUtil().findUiObject(trackNameSelector);
        if (trackNameText != null) {
            trackNameText.click();
        }
    }

    /**
     * Scrolls through the list in search of the provided menu
     *
     * @param menu : menu to search
     * @return UiObject found for the menu searched
     */
    private UiObject selectByName(String menu) throws UiObjectNotFoundException {
        UiObject menuListItem = null;

        /**
         * TODO - Keeping the empty functions for now, to avoid the compilation error in Vendor it
         * will be removed after vendor clean up (b/266449779)
         */
        return menuListItem;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPlaying() {
        List<MediaController> controllers = mMediaSessionManager.getActiveSessions(null);
        if (controllers.size() == 0) {
            throw new RuntimeException("Unable to find Media Controller");
        }
        PlaybackState state = controllers.get(0).getPlaybackState();
        return state.getState() == PlaybackState.STATE_PLAYING;
    }

    /** {@inheritDoc} */
    @Override
    public String getMediaAppTitle() {
        BySelector mediaAppTitleSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_TITLE);
        UiObject2 mediaAppTitle = getSpectatioUiUtil().findUiObject(mediaAppTitleSelector);
        validateUiObject(mediaAppTitle, AutomotiveConfigConstants.MEDIA_APP_TITLE);
        return mediaAppTitle.getText();
    }

    /** {@inheritDoc} */
    @Override
    public void openMediaAppMenuItems() {
        BySelector mediaDropDownMenuSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_DROP_DOWN_MENU);
        List<UiObject2> menuItemElements =
                getSpectatioUiUtil().findUiObjects(mediaDropDownMenuSelector);
        validateUiObject(menuItemElements, AutomotiveConfigConstants.MEDIA_APP_DROP_DOWN_MENU);
        if (menuItemElements.size() == 0) {
            throw new UnknownUiException("Unable to find Media drop down.");
        }
        // Media menu drop down is the last item in Media App Screen
        int positionOfMenuItemDropDown = menuItemElements.size() - 1;
        getSpectatioUiUtil().clickAndWait(menuItemElements.get(positionOfMenuItemDropDown));
    }

    /** {@inheritDoc} */
    @Override
    public boolean areMediaAppsPresent(List<String> mediaAppsNames) {
        BySelector mediaAppPageTitleSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APPS_GRID_TITLE);
        UiObject2 mediaAppPageTitle = getSpectatioUiUtil().findUiObject(mediaAppPageTitleSelector);
        validateUiObject(mediaAppPageTitle, AutomotiveConfigConstants.MEDIA_APPS_GRID_TITLE);
        if (mediaAppsNames == null || mediaAppsNames.size() == 0) {
            return false;
        }
        // Scroll and find media apps in Media App Grid
        for (String expectedApp : mediaAppsNames) {
            UiObject2 mediaApp =
                    scrollAndFindApp(
                            By.text(Pattern.compile(expectedApp, Pattern.CASE_INSENSITIVE)));
            if (mediaApp == null || !mediaApp.getText().equals(expectedApp)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void openApp(String appName) {
        getSpectatioUiUtil().wait1Second(); // to avoid stale object error
        UiObject2 app = scrollAndFindApp(By.text(appName));
        if (app != null) {
            getSpectatioUiUtil().clickAndWait(app);
        } else {
            throw new IllegalStateException(String.format("App %s cannot be found", appName));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void openMediaAppSettingsPage() {
        BySelector menuItemElementSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_DROP_DOWN_MENU);
        List<UiObject2> menuItemElements =
                getSpectatioUiUtil().findUiObjects(menuItemElementSelector);
        validateUiObject(menuItemElements, AutomotiveConfigConstants.MEDIA_APP_DROP_DOWN_MENU);
        int settingsItemPosition = menuItemElements.size() - 2;
        getSpectatioUiUtil().clickAndWait(menuItemElements.get(settingsItemPosition));
    }

    /** {@inheritDoc} */
    @Override
    public String getMediaAppUserNotLoggedInErrorMessage() {
        BySelector noLoginMsgSelector =
                getUiElementFromConfig(AutomotiveConfigConstants.MEDIA_APP_NO_LOGIN_MSG);
        UiObject2 noLoginMsg = getSpectatioUiUtil().findUiObject(noLoginMsgSelector);
        validateUiObject(noLoginMsg, AutomotiveConfigConstants.MEDIA_APP_NO_LOGIN_MSG);
        return noLoginMsg.getText();
    }

    /** {@inheritDoc} */
    @Override
    public void selectMediaTrack(String... menuOptions) {
        for (String option : menuOptions) {
            UiObject2 mediaTrack =
                    scrollAndFindApp(By.text(Pattern.compile(option, Pattern.CASE_INSENSITIVE)));
            validateUiObject(mediaTrack, String.format("media track: %s", option));
            mediaTrack.click();
            getSpectatioUiUtil().waitForIdle();
        }
    }

    private UiObject2 scrollAndFindApp(BySelector selector) {

        UiObject2 object =
                mScrollUtility.scrollAndFindUiObject(
                        mScrollAction,
                        mScrollDirection,
                        mForwardButtonSelector,
                        mBackwardButtonSelector,
                        mScrollableElementSelector,
                        selector,
                        String.format("Scroll through media app grid to find %s", selector));
        validateUiObject(object, String.format("Given media app %s", selector));
        return object;
    }

    private void validateUiObject(UiObject2 uiObject, String action) {
        if (uiObject == null) {
            throw new UnknownUiException(
                    String.format("Unable to find UI Element for %s.", action));
        }
    }

    private void validateUiObject(List<UiObject2> uiObjects, String action) {
        if (uiObjects == null) {
            throw new UnknownUiException(
                    String.format("Unable to find UI Element for %s.", action));
        }
    }
}
