/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.permissioncontroller.permission.ui.auto;

import static com.android.permissioncontroller.Constants.EXTRA_SESSION_ID;
import static com.android.permissioncontroller.Constants.INVALID_SESSION_ID;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW_ALWAYS;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW_FOREGROUND;
import static com.android.permissioncontroller.PermissionControllerStatsLog.APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__DENY;
import static com.android.permissioncontroller.permission.ui.ManagePermissionsActivity.EXTRA_RESULT_PERMISSION_INTERACTED;
import static com.android.permissioncontroller.permission.ui.ManagePermissionsActivity.EXTRA_RESULT_PERMISSION_RESULT;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.BidiFormatter;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.TypedArrayUtils;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.android.car.ui.AlertDialogBuilder;
import com.android.permissioncontroller.R;
import com.android.permissioncontroller.auto.AutoSettingsFrameFragment;
import com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler;
import com.android.permissioncontroller.permission.ui.model.AppPermissionViewModel;
import com.android.permissioncontroller.permission.ui.model.AppPermissionViewModelFactory;
import com.android.permissioncontroller.permission.utils.KotlinUtils;
import com.android.permissioncontroller.permission.utils.PackageRemovalMonitor;
import com.android.settingslib.RestrictedLockUtils;

import java.util.Map;

import kotlin.Pair;

/** Settings related to a particular permission for the given app. */
public class AutoAppPermissionFragment extends AutoSettingsFrameFragment
        implements AppPermissionViewModel.ConfirmDialogShowingFragment {

    private static final String LOG_TAG = "AppPermissionFragment";
    private static final long POST_DELAY_MS = 20;

    @NonNull
    private TwoStatePreference mAllowPermissionPreference;
    @NonNull
    private TwoStatePreference mAlwaysPermissionPreference;
    @NonNull
    private TwoStatePreference mForegroundOnlyPermissionPreference;
    @NonNull
    private TwoStatePreference mDenyPermissionPreference;
    @NonNull
    private AutoTwoTargetPreference mDetailsPreference;

    @NonNull
    private AppPermissionViewModel mViewModel;
    @NonNull
    private String mPackageName;
    @NonNull
    private String mPermGroupName;
    @NonNull
    private UserHandle mUser;
    @NonNull
    private String mPackageLabel;
    @NonNull
    private String mPermGroupLabel;
    private Drawable mPackageIcon;

    /**
     * Listens for changes to the app the permission is currently getting granted to. {@code null}
     * when unregistered.
     */
    @Nullable
    private PackageRemovalMonitor mPackageRemovalMonitor;

    /**
     * Returns a new {@link AutoAppPermissionFragment}.
     *
     * @param packageName the package name for which the permission is being changed
     * @param permName the name of the permission being changed
     * @param groupName the name of the permission group being changed
     * @param userHandle the user for which the permission is being changed
     */
    @NonNull
    public static AutoAppPermissionFragment newInstance(@NonNull String packageName,
            @NonNull String permName, @Nullable String groupName, @NonNull UserHandle userHandle,
            @NonNull long sessionId) {
        AutoAppPermissionFragment fragment = new AutoAppPermissionFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        if (groupName == null) {
            arguments.putString(Intent.EXTRA_PERMISSION_NAME, permName);
        } else {
            arguments.putString(Intent.EXTRA_PERMISSION_GROUP_NAME, groupName);
        }
        arguments.putParcelable(Intent.EXTRA_USER, userHandle);
        arguments.putLong(EXTRA_SESSION_ID, sessionId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackageName = getArguments().getString(Intent.EXTRA_PACKAGE_NAME);
        mPermGroupName = getArguments().getString(Intent.EXTRA_PERMISSION_GROUP_NAME);
        if (mPermGroupName == null) {
            mPermGroupName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
        }
        mUser = getArguments().getParcelable(Intent.EXTRA_USER);
        mPackageLabel = BidiFormatter.getInstance().unicodeWrap(
                KotlinUtils.INSTANCE.getPackageLabel(getActivity().getApplication(), mPackageName,
                        mUser));
        mPermGroupLabel = KotlinUtils.INSTANCE.getPermGroupLabel(getContext(),
                mPermGroupName).toString();
        mPackageIcon = KotlinUtils.INSTANCE.getBadgedPackageIcon(getActivity().getApplication(),
                mPackageName, mUser);
        setHeaderLabel(
                requireContext().getString(R.string.app_permission_title, mPermGroupLabel));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        PreferenceScreen screen = getPreferenceScreen();
        screen.addPreference(
                AutoPermissionsUtils.createHeaderPreference(requireContext(),
                        mPackageIcon, mPackageName, mPackageLabel));

        // Add permissions selector preferences.
        PreferenceGroup permissionSelector = new PreferenceCategory(requireContext());
        permissionSelector.setTitle(
                getString(R.string.app_permission_header, mPermGroupLabel));
        screen.addPreference(permissionSelector);

        mAllowPermissionPreference = new SelectedPermissionPreference(requireContext());
        mAllowPermissionPreference.setTitle(R.string.app_permission_button_allow);
        permissionSelector.addPreference(mAllowPermissionPreference);

        mAlwaysPermissionPreference = new SelectedPermissionPreference(requireContext());
        mAlwaysPermissionPreference.setTitle(R.string.app_permission_button_allow_always);
        permissionSelector.addPreference(mAlwaysPermissionPreference);

        mForegroundOnlyPermissionPreference = new SelectedPermissionPreference(requireContext());
        mForegroundOnlyPermissionPreference.setTitle(
                R.string.app_permission_button_allow_foreground);
        permissionSelector.addPreference(mForegroundOnlyPermissionPreference);

        mDenyPermissionPreference = new SelectedPermissionPreference(requireContext());
        mDenyPermissionPreference.setTitle(R.string.app_permission_button_deny);
        permissionSelector.addPreference(mDenyPermissionPreference);

        mAllowPermissionPreference.setOnPreferenceClickListener(v -> {
            checkOnlyOneButtonOverride(AppPermissionViewModel.ButtonType.ALLOW);
            setResult(GrantPermissionsViewHandler.GRANTED_ALWAYS);
            requestChange(AppPermissionViewModel.ChangeRequest.GRANT_FOREGROUND,
                    APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW);
            return true;
        });
        mAlwaysPermissionPreference.setOnPreferenceClickListener(v -> {
            checkOnlyOneButtonOverride(AppPermissionViewModel.ButtonType.ALLOW_ALWAYS);
            setResult(GrantPermissionsViewHandler.GRANTED_ALWAYS);
            requestChange(AppPermissionViewModel.ChangeRequest.GRANT_BOTH,
                    APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW_ALWAYS);
            return true;
        });
        mForegroundOnlyPermissionPreference.setOnPreferenceClickListener(v -> {
            checkOnlyOneButtonOverride(AppPermissionViewModel.ButtonType.ALLOW_FOREGROUND);
            setResult(GrantPermissionsViewHandler.GRANTED_FOREGROUND_ONLY);
            requestChange(AppPermissionViewModel.ChangeRequest.GRANT_FOREGROUND_ONLY,
                    APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__ALLOW_FOREGROUND);
            return true;
        });
        mDenyPermissionPreference.setOnPreferenceClickListener(v -> {
            checkOnlyOneButtonOverride(AppPermissionViewModel.ButtonType.DENY);
            setResult(GrantPermissionsViewHandler.DENIED);
            requestChange(AppPermissionViewModel.ChangeRequest.REVOKE_BOTH,
                    APP_PERMISSION_FRAGMENT_ACTION_REPORTED__BUTTON_PRESSED__DENY);
            return true;
        });

        mDetailsPreference = new AutoTwoTargetPreference(requireContext());
        screen.addPreference(mDetailsPreference);
    }

    @Override
    public void onStart() {
        super.onStart();
        Activity activity = requireActivity();

        // Get notified when the package is removed.
        mPackageRemovalMonitor = new PackageRemovalMonitor(requireContext(), mPackageName) {
            @Override
            public void onPackageRemoved() {
                Log.w(LOG_TAG, mPackageName + " was uninstalled");
                activity.setResult(Activity.RESULT_CANCELED);
                activity.finish();
            }
        };
        mPackageRemovalMonitor.register();

        // Check if the package was removed while this activity was not started.
        try {
            activity.createPackageContextAsUser(mPackageName, /* flags= */ 0,
                    mUser).getPackageManager().getPackageInfo(mPackageName,
                    /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(LOG_TAG, mPackageName + " was uninstalled while this activity was stopped", e);
            activity.setResult(Activity.RESULT_CANCELED);
            activity.finish();
        }

        long sessionId = getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID);
        AppPermissionViewModelFactory factory = new AppPermissionViewModelFactory(
                getActivity().getApplication(), mPackageName, mPermGroupName, mUser, sessionId);
        mViewModel = new ViewModelProvider(this, factory).get(AppPermissionViewModel.class);
        mViewModel.getButtonStateLiveData().observe(this, this::setRadioButtonsState);
        mViewModel.getDetailResIdLiveData().observe(this, this::setDetail);
        mViewModel.getShowAdminSupportLiveData().observe(this, this::setAdminSupportDetail);
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mPackageRemovalMonitor != null) {
            mPackageRemovalMonitor.unregister();
            mPackageRemovalMonitor = null;
        }
    }

    @Override
    public void showConfirmDialog(AppPermissionViewModel.ChangeRequest changeRequest, int messageId,
            int buttonPressed, boolean oneTime) {
        Bundle args = new Bundle();

        args.putInt(DefaultDenyDialog.MSG, messageId);
        args.putSerializable(DefaultDenyDialog.CHANGE_REQUEST, changeRequest);
        args.putSerializable(DefaultDenyDialog.BUTTON, buttonPressed);

        DefaultDenyDialog defaultDenyDialog = new DefaultDenyDialog();
        defaultDenyDialog.setArguments(args);
        defaultDenyDialog.setTargetFragment(this, 0);
        defaultDenyDialog.show(requireFragmentManager().beginTransaction(),
                DefaultDenyDialog.class.getName());
    }

    private void setResult(@GrantPermissionsViewHandler.Result int result) {
        Intent intent = new Intent()
                .putExtra(EXTRA_RESULT_PERMISSION_INTERACTED,
                        requireArguments().getString(Intent.EXTRA_PERMISSION_GROUP_NAME))
                .putExtra(EXTRA_RESULT_PERMISSION_RESULT, result);
        requireActivity().setResult(Activity.RESULT_OK, intent);
    }

    private void setRadioButtonsState(
            Map<AppPermissionViewModel.ButtonType, AppPermissionViewModel.ButtonState> states) {
        setButtonState(mAllowPermissionPreference,
                states.get(AppPermissionViewModel.ButtonType.ALLOW));
        setButtonState(mAlwaysPermissionPreference,
                states.get(AppPermissionViewModel.ButtonType.ALLOW_ALWAYS));
        setButtonState(mForegroundOnlyPermissionPreference,
                states.get(AppPermissionViewModel.ButtonType.ALLOW_FOREGROUND));
        setButtonState(mDenyPermissionPreference,
                states.get(AppPermissionViewModel.ButtonType.DENY));
    }

    private void setButtonState(TwoStatePreference button,
            AppPermissionViewModel.ButtonState state) {
        button.setVisible(state.isShown());
        if (state.isShown()) {
            button.setChecked(state.isChecked());
            button.setEnabled(state.isEnabled());
        }
    }

    /**
     * Helper method to handle the UX edge case where the confirmation dialog is shown and two
     * buttons are selected at once. This happens since the Auto UI doesn't use a proper radio
     * group, so there is nothing that enforces that tapping on a button unchecks a previously
     * checked button. Apart from this case, this UI is not necessary since the UI is entirely
     * driven by the ViewModel.
     */
    private void checkOnlyOneButtonOverride(AppPermissionViewModel.ButtonType buttonType) {
        mAllowPermissionPreference.setChecked(
                buttonType == AppPermissionViewModel.ButtonType.ALLOW);
        mAlwaysPermissionPreference.setChecked(
                buttonType == AppPermissionViewModel.ButtonType.ALLOW_ALWAYS);
        mForegroundOnlyPermissionPreference.setChecked(
                buttonType == AppPermissionViewModel.ButtonType.ALLOW_FOREGROUND);
        mDenyPermissionPreference.setChecked(buttonType == AppPermissionViewModel.ButtonType.DENY);
    }

    private void setDetail(Pair<Integer, Integer> detailResIds) {
        if (detailResIds == null) {
            mDetailsPreference.setVisible(false);
            return;
        }
        if (detailResIds.getSecond() != null) {
            mDetailsPreference.setWidgetLayoutResource(R.layout.settings_preference_widget);
            mDetailsPreference.setOnSecondTargetClickListener(
                    v -> showAllPermissions(mPermGroupName));
            mDetailsPreference.setSummary(
                    getString(detailResIds.getFirst(), detailResIds.getSecond()));
        } else {
            mDetailsPreference.setSummary(detailResIds.getFirst());
        }
    }

    /**
     * Show all individual permissions in this group in a new fragment.
     */
    private void showAllPermissions(@NonNull String filterGroup) {
        Fragment frag = AutoAllAppPermissionsFragment.newInstance(mPackageName,
                filterGroup, mUser,
                getArguments().getLong(EXTRA_SESSION_ID, INVALID_SESSION_ID));
        requireFragmentManager().beginTransaction()
                .replace(android.R.id.content, frag)
                .addToBackStack("AllPerms")
                .commit();
    }

    private void setAdminSupportDetail(RestrictedLockUtils.EnforcedAdmin admin) {
        if (admin != null) {
            mDetailsPreference.setWidgetLayoutResource(R.layout.info_preference_widget);
            mDetailsPreference.setOnSecondTargetClickListener(v ->
                    RestrictedLockUtils.sendShowAdminSupportDetailsIntent(getContext(), admin)
            );
        }
    }

    /**
     * Request to grant/revoke permissions group.
     */
    private void requestChange(AppPermissionViewModel.ChangeRequest changeRequest,
            int buttonClicked) {
        mViewModel.requestChange(/* setOneTime= */false, /* fragment= */ this,
                /* defaultDeny= */this, changeRequest, buttonClicked);
    }

    /** Preference used to represent apps that can be picked as a default app. */
    private static class SelectedPermissionPreference extends TwoStatePreference {

        SelectedPermissionPreference(Context context) {
            super(context, null, TypedArrayUtils.getAttr(context, R.attr.preferenceStyle,
                    android.R.attr.preferenceStyle));
            setPersistent(false);
            setLayoutResource(R.layout.car_radio_button_preference);
            setWidgetLayoutResource(R.layout.radio_button_preference_widget);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);

            RadioButton radioButton = (RadioButton) holder.findViewById(R.id.radio_button);
            radioButton.setChecked(isChecked());
        }
    }

    /**
     * A dialog warning the user that they are about to deny a permission that was granted by
     * default.
     *
     * @see #showConfirmDialog(AppPermissionViewModel.ChangeRequest, int, int, boolean)
     */
    public static class DefaultDenyDialog extends DialogFragment {
        private static final String MSG = DefaultDenyDialog.class.getName() + ".arg.msg";
        private static final String CHANGE_REQUEST = DefaultDenyDialog.class.getName()
                + ".arg.changeRequest";
        private static final String BUTTON = DefaultDenyDialog.class.getName()
                + ".arg.button";

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AutoAppPermissionFragment fragment = (AutoAppPermissionFragment) getTargetFragment();
            return new AlertDialogBuilder(getContext())
                    .setMessage(requireArguments().getInt(MSG))
                    .setNegativeButton(R.string.cancel,
                            (dialog, which) -> dialog.cancel())
                    .setPositiveButton(R.string.grant_dialog_button_deny_anyway,
                            (dialog, which) ->
                                    fragment.mViewModel.onDenyAnyWay(
                                            (AppPermissionViewModel.ChangeRequest)
                                                    getArguments().getSerializable(CHANGE_REQUEST),
                                            getArguments().getInt(BUTTON),
                                            false))
                    .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            AutoAppPermissionFragment fragment = (AutoAppPermissionFragment) getTargetFragment();
            fragment.setRadioButtonsState(fragment.mViewModel.getButtonStateLiveData().getValue());
        }
    }
}
