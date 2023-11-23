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

package com.android.systemui.car.qc;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.car.qc.controller.BaseQCController;
import com.android.car.qc.controller.LocalQCController;
import com.android.car.qc.controller.RemoteQCController;
import com.android.car.qc.provider.BaseLocalQCProvider;
import com.android.systemui.settings.UserTracker;

import java.lang.reflect.Constructor;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Class to control instances of {@link SystemUIQCView}. This controller is responsible for
 * attaching either a remote controller for the current user or a local controller using a dagger
 * injected constructor and fall back to a default {@link Context} constructor when not present.
 */
public final class SystemUIQCViewController {
    private static final String TAG = SystemUIQCViewController.class.getName();
    private final Context mContext;
    private final UserTracker mUserTracker;
    private final Map<Class<?>, Provider<BaseLocalQCProvider>> mLocalQCProviderCreators;
    private SystemUIQCView mView;
    private BaseQCController mController;
    private boolean mUserChangedCallbackRegistered;

    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, Context userContext) {
            rebindController();
        }
    };

    @Inject
    public SystemUIQCViewController(Context context, UserTracker userTracker,
            Map<Class<?>, Provider<BaseLocalQCProvider>> localQCProviderCreators) {
        mContext = context;
        mUserTracker = userTracker;
        mLocalQCProviderCreators = localQCProviderCreators;
    }

    /**
     * Attaches a {@link SystemUIQCView} to this controller.
     */
    public void attachView(SystemUIQCView view) {
        mView = view;
        if (mView.getRemoteUriString() != null) {
            Uri uri = Uri.parse(mView.getRemoteUriString());
            if (uri.getUserInfo() == null) {
                // To bind to the content provider as the current user rather than user 0 (which
                // SystemUI is running on), add the current user id followed by the '@' symbol
                // before the Uri's authority.
                uri = uri.buildUpon().authority(
                        String.format("%s@%s", mUserTracker.getUserId(),
                                uri.getAuthority())).build();
            }
            bindRemoteQCView(uri);
            if (!mUserChangedCallbackRegistered) {
                mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
                mUserChangedCallbackRegistered = true;
            }
        } else if (mView.getLocalClassString() != null) {
            bindLocalQCView(mView.getLocalClassString());
        }
    }

    /**
     * Toggles whether or not this view should listen to live updates.
     */
    public void listen(boolean shouldListen) {
        if (mController != null) {
            mController.listen(shouldListen);
        }
    }

    /**
     * Destroys the current QCView and associated controller.
     */
    public void destroy() {
        resetViewAndController();
        if (mUserChangedCallbackRegistered) {
            mUserTracker.removeCallback(mUserChangedCallback);
            mUserChangedCallbackRegistered = false;
        }
    }

    private void rebindController() {
        if (mView == null) {
            return;
        }
        resetViewAndController();
        attachView(mView);
    }

    private void resetViewAndController() {
        if (mController != null) {
            mController.destroy();
            mController = null;
        }
        if (mView != null) {
            mView.onChanged(/* qcItem= */ null);
        }
    }

    private void bindRemoteQCView(Uri uri) {
        mController = new RemoteQCController(mContext, uri);
        mController.addObserver(mView);
        mController.bind();
    }

    private void bindLocalQCView(String localClass) {
        BaseLocalQCProvider localQCProvider = createLocalQCProviderInstance(localClass, mContext);
        mController = new LocalQCController(mContext, localQCProvider);
        mController.addObserver(mView);
        mController.bind();
    }

    private BaseLocalQCProvider createLocalQCProviderInstance(String controllerName,
            Context context) {
        try {
            BaseLocalQCProvider injectedProvider = resolveInjectedLocalQCProviderInstance(
                    controllerName);
            if (injectedProvider != null) {
                return injectedProvider;
            }
            Class<?> clazz = Class.forName(controllerName);
            Constructor<?> providerConstructor = clazz.getConstructor(Context.class);
            return (BaseLocalQCProvider) providerConstructor.newInstance(context);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Invalid controller: " + controllerName, e);
        }
    }

    private BaseLocalQCProvider resolveInjectedLocalQCProviderInstance(@Nullable String className) {
        try {
            Class<?> clazz = Class.forName(className);
            Provider<BaseLocalQCProvider> provider = mLocalQCProviderCreators.get(clazz);
            return provider == null ? null : provider.get();
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "Could not find class " + className);
            return null;
        }
    }
}
