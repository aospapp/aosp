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

package com.android.server.sdksandbox;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.app.adservices.AdServicesManager.AD_SERVICES_SYSTEM_SERVICE;
import static android.app.sdksandbox.SdkSandboxManager.ACTION_START_SANDBOXED_ACTIVITY;
import static android.app.sdksandbox.SdkSandboxManager.EXTRA_SANDBOXED_ACTIVITY_HANDLER;
import static android.app.sdksandbox.SdkSandboxManager.LOAD_SDK_INTERNAL_ERROR;
import static android.app.sdksandbox.SdkSandboxManager.LOAD_SDK_SDK_SANDBOX_DISABLED;
import static android.app.sdksandbox.SdkSandboxManager.REQUEST_SURFACE_PACKAGE_SDK_NOT_LOADED;
import static android.app.sdksandbox.SdkSandboxManager.SDK_SANDBOX_PROCESS_NOT_AVAILABLE;
import static android.app.sdksandbox.SdkSandboxManager.SDK_SANDBOX_SERVICE;

import static com.android.sdksandbox.service.stats.SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__REQUEST_SURFACE_PACKAGE;
import static com.android.sdksandbox.service.stats.SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__UNLOAD_SDK;
import static com.android.sdksandbox.service.stats.SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX;
import static com.android.server.sdksandbox.SdkSandboxStorageManager.StorageDirInfo;
import static com.android.server.wm.ActivityInterceptorCallback.MAINLINE_SDK_SANDBOX_ORDER_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.sdksandbox.ILoadSdkCallback;
import android.app.sdksandbox.IRequestSurfacePackageCallback;
import android.app.sdksandbox.ISdkSandboxManager;
import android.app.sdksandbox.ISdkSandboxProcessDeathCallback;
import android.app.sdksandbox.ISdkToServiceCallback;
import android.app.sdksandbox.ISharedPreferencesSyncCallback;
import android.app.sdksandbox.LoadSdkException;
import android.app.sdksandbox.LogUtil;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.SharedPreferencesUpdate;
import android.app.sdksandbox.sdkprovider.SdkSandboxController;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.webkit.WebViewUpdateService;

import androidx.annotation.RequiresApi;

import com.android.adservices.AdServicesCommon;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.build.SdkLevel;
import com.android.sdksandbox.IComputeSdkStorageCallback;
import com.android.sdksandbox.IRequestSurfacePackageFromSdkCallback;
import com.android.sdksandbox.ISdkSandboxDisabledCallback;
import com.android.sdksandbox.ISdkSandboxService;
import com.android.sdksandbox.service.stats.SdkSandboxStatsLog;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerLocal;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityInterceptorCallbackRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link SdkSandboxManager}.
 *
 * @hide
 */
public class SdkSandboxManagerService extends ISdkSandboxManager.Stub {

    private static final String TAG = "SdkSandboxManager";

    private static final String STOP_SDK_SANDBOX_PERMISSION =
            "com.android.app.sdksandbox.permission.STOP_SDK_SANDBOX";

    private static final String SANDBOX_NOT_AVAILABLE_MSG = "Sandbox is unavailable";
    private static final String SANDBOX_DISABLED_MSG = "SDK sandbox is disabled";

    private static final String DUMP_ARG_AD_SERVICES = "--AdServices";

    private final Context mContext;

    private final ActivityManager mActivityManager;
    private final ActivityManagerLocal mActivityManagerLocal;
    private final Handler mHandler;
    private final SdkSandboxStorageManager mSdkSandboxStorageManager;
    private final SdkSandboxServiceProvider mServiceProvider;

    @GuardedBy("mLock")
    private IBinder mAdServicesManager;

    // TODO(b/282239822): temporary guard to define if dump() should handle the --AdServices otpion
    @GuardedBy("mLock")
    private boolean mAdServicesManagerPublished;

    private final Object mLock = new Object();

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    /**
     * For each app, keep a mapping from SDK name to it's corresponding LoadSdkSession. This can
     * contain all SDKs that are pending load, have been loaded, unloaded etc. Therefore, it is
     * important to filter out by the type needed.
     */
    @GuardedBy("mLock")
    private final ArrayMap<CallingInfo, ArrayMap<String, LoadSdkSession>> mLoadSdkSessions =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<CallingInfo, IBinder> mCallingInfosWithDeathRecipients =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final Set<CallingInfo> mRunningInstrumentations = new ArraySet<>();

    @GuardedBy("mLock")
    private final ArrayMap<CallingInfo, RemoteCallbackList<ISdkSandboxProcessDeathCallback>>
            mSandboxLifecycleCallbacks = new ArrayMap<>();

    // Callbacks that need to be invoked when the sandbox binding has occurred (either successfully
    // or unsuccessfully).
    @GuardedBy("mLock")
    private final ArrayMap<CallingInfo, ArrayList<SandboxBindingCallback>>
            mSandboxBindingCallbacks = new ArrayMap<>();

    @GuardedBy("mLock")
    private final ArrayMap<CallingInfo, ISharedPreferencesSyncCallback> mSyncDataCallbacks =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final UidImportanceListener mUidImportanceListener = new UidImportanceListener();

    private final String mAdServicesPackageName;

    private Injector mInjector;

    private final SdkSandboxPulledAtoms mSdkSandboxPulledAtoms;

    // All U devices have the visibility patch, so we can consider it already checked on U+ devices.
    private static final boolean DEFAULT_VALUE_VISIBILITY_PATCH_CHECKED = SdkLevel.isAtLeastU();

    // The device must have a change that allows the Webview provider to be visible in order for the
    // sandbox to be enabled. This change is present on all U+ devices, but not all T devices.
    @GuardedBy("mLock")
    private boolean mHasVisibilityPatch = DEFAULT_VALUE_VISIBILITY_PATCH_CHECKED;

    @GuardedBy("mLock")
    private boolean mCheckedVisibilityPatch = DEFAULT_VALUE_VISIBILITY_PATCH_CHECKED;

    private SdkSandboxSettingsListener mSdkSandboxSettingsListener;

    private static final String PROPERTY_DISABLE_SDK_SANDBOX = "disable_sdk_sandbox";
    private static final String PROPERTY_CUSTOMIZED_SDK_CONTEXT_ENABLED =
            "sdksandbox_customized_sdk_context_enabled";
    private static final boolean DEFAULT_VALUE_DISABLE_SDK_SANDBOX = true;
    private static final boolean DEFAULT_VALUE_CUSTOMIZED_SDK_CONTEXT_ENABLED = false;

    /**
     * Property to enforce broadcast receiver restrictions for SDK sandbox processes. If the value
     * of this property is {@code true}, the restrictions will be enforced.
     */
    private static final String PROPERTY_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS =
            "enforce_broadcast_receiver_restrictions";

    private static final boolean DEFAULT_VALUE_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS = false;

    /**
     * Property to enforce content provider restrictions for SDK sandbox processes. If the value of
     * this property is {@code true}, the restrictions will be enforced.
     */
    private static final String PROPERTY_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS =
            "enforce_content_provider_restrictions";

    private static final boolean DEFAULT_VALUE_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS = false;

    // On UDC, AdServicesManagerService.Lifecycle implements dumpable so it's dumped as part of
    // SystemServer.
    // If AdServices register itself as binder service, dump() will ignore the --AdServices option
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_AD_SERVICES_ITSELF =
            "Don't need to dump AdServices as it's available as " + AD_SERVICES_SYSTEM_SERVICE;

    // On UDC, if AdServices register itself as binder service, dump() will ignore the --AdServices
    // option because AdServices could be dumped as part of SystemService
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_SYSTEM_SERVICE =
            "Don't need to dump AdServices on UDC+ - use "
                    + "'dumpsys system_server_dumper --name AdServices instead'";

    static class Injector {
        private final Context mContext;
        private SdkSandboxManagerLocal mLocalManager;
        private final SdkSandboxServiceProvider mServiceProvider;

        Injector(Context context) {
            mContext = context;
            mServiceProvider = new SdkSandboxServiceProviderImpl(mContext);
        }

        private static final boolean IS_EMULATOR =
                SystemProperties.getBoolean("ro.boot.qemu", false);

        long getCurrentTime() {
            return System.currentTimeMillis();
        }

        SdkSandboxShellCommand createShellCommand(
                SdkSandboxManagerService service, Context context) {
            return new SdkSandboxShellCommand(service, context);
        }

        boolean isEmulator() {
            return IS_EMULATOR;
        }

        SdkSandboxServiceProvider getSdkSandboxServiceProvider() {
            return mServiceProvider;
        }

        SdkSandboxPulledAtoms getSdkSandboxPulledAtoms() {
            return new SdkSandboxPulledAtoms();
        }

        PackageManagerLocal getPackageManagerLocal() {
            return LocalManagerRegistry.getManager(PackageManagerLocal.class);
        }

        SdkSandboxStorageManager getSdkSandboxStorageManager() {
            return new SdkSandboxStorageManager(mContext, mLocalManager, getPackageManagerLocal());
        }

        void setLocalManager(SdkSandboxManagerLocal localManager) {
            mLocalManager = localManager;
        }

        SdkSandboxManagerLocal getLocalManager() {
            return mLocalManager;
        }
    }

    SdkSandboxManagerService(Context context) {
        this(context, new Injector(context));
    }

    @VisibleForTesting
    SdkSandboxManagerService(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mInjector.setLocalManager(new LocalImpl());
        mServiceProvider = mInjector.getSdkSandboxServiceProvider();
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mActivityManagerLocal = LocalManagerRegistry.getManager(ActivityManagerLocal.class);
        mSdkSandboxPulledAtoms = mInjector.getSdkSandboxPulledAtoms();
        PackageManagerLocal packageManagerLocal =
                LocalManagerRegistry.getManager(PackageManagerLocal.class);
        mSdkSandboxStorageManager = mInjector.getSdkSandboxStorageManager();

        // Start the handler thread.
        HandlerThread handlerThread = new HandlerThread("SdkSandboxManagerServiceHandler");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());

        // Start a background handler thread.
        HandlerThread backgroundHandlerThread =
                new HandlerThread(
                        "SdkSandboxManagerServiceHandler", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundHandlerThread.start();

        registerBroadcastReceivers();

        mAdServicesPackageName = resolveAdServicesPackage();
        mSdkSandboxSettingsListener = new SdkSandboxSettingsListener(mContext);
        mSdkSandboxSettingsListener.registerObserver();
        mSdkSandboxPulledAtoms.initialize(mContext);

        if (SdkLevel.isAtLeastU()) {
            registerSandboxActivityInterceptor();
        }
    }

    private void registerBroadcastReceivers() {
        registerPackageUpdateBroadcastReceiver();
        registerVerifierBroadcastReceiver();
    }

    private void registerPackageUpdateBroadcastReceiver() {
        // Register for package addition and update
        final IntentFilter packageAddedIntentFilter = new IntentFilter();
        packageAddedIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageAddedIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageAddedIntentFilter.addDataScheme("package");
        BroadcastReceiver packageAddedIntentReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final String packageName = intent.getData().getSchemeSpecificPart();
                        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        final CallingInfo callingInfo = new CallingInfo(uid, packageName);
                        mHandler.post(
                                () ->
                                        mSdkSandboxStorageManager.onPackageAddedOrUpdated(
                                                callingInfo));
                    }
                };
        mContext.registerReceiver(
                packageAddedIntentReceiver,
                packageAddedIntentFilter,
                /*broadcastPermission=*/ null,
                mHandler);
    }

    private void registerVerifierBroadcastReceiver() {
        final IntentFilter packageNeedsVerificationIntentFilter = new IntentFilter();
        try {
            packageNeedsVerificationIntentFilter.addDataType(PACKAGE_MIME_TYPE);
            packageNeedsVerificationIntentFilter.addAction(
                    Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
            mContext.registerReceiverForAllUsers(
                    new SdkSandboxVerifierReceiver(),
                    packageNeedsVerificationIntentFilter,
                    /*broadcastPermission=*/ null,
                    /*scheduler=*/ null,
                    Context.RECEIVER_EXPORTED);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.e(TAG, "Could not register verifier");
        }
    }

    @Override
    public List<SandboxedSdk> getSandboxedSdks(
            String callingPackageName, long timeAppCalledSystemServer) {
        final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

        final int callingUid = Binder.getCallingUid();
        final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__GET_SANDBOXED_SDKS,
                /*latency=*/ (int)
                        (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                callingUid);

        final List<SandboxedSdk> sandboxedSdks = new ArrayList<>();
        synchronized (mLock) {
            ArrayList<LoadSdkSession> loadedSdks = getLoadedSdksForApp(callingInfo);
            for (int i = 0; i < loadedSdks.size(); i++) {
                LoadSdkSession sdk = loadedSdks.get(i);
                SandboxedSdk sandboxedSdk = sdk.getSandboxedSdk();
                if (sandboxedSdk != null) {
                    sandboxedSdks.add(sandboxedSdk);
                } else {
                    Log.w(
                            TAG,
                            "SandboxedSdk is null for SDK "
                                    + sdk.mSdkName
                                    + " despite being loaded");
                }
            }
        }
        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__GET_SANDBOXED_SDKS,
                /*latency=*/ (int)
                        (mInjector.getCurrentTime() - timeSystemServerReceivedCallFromApp),
                /*success=*/ true,
                SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                callingUid);
        return sandboxedSdks;
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void registerSandboxActivityInterceptor() {
        final ActivityInterceptorCallback mActivityInterceptorCallback =
                info -> {
                    final Intent intent = info.getIntent();
                    final String sdkSandboxPackageName =
                            mContext.getPackageManager().getSdkSandboxPackageName();
                    // Only intercept if action and package are both defined and refer to the
                    // sandbox activity.
                    if (intent == null
                            || intent.getPackage() == null
                            || !intent.getPackage().equals(sdkSandboxPackageName)
                            || intent.getAction() == null
                            || !intent.getAction().equals(ACTION_START_SANDBOXED_ACTIVITY)) {
                        return null;
                    }

                    // If component is set, it should refer to the sandbox package to intercept.
                    if (intent.getComponent() != null) {
                        final String componentPackageName = intent.getComponent().getPackageName();
                        if (!componentPackageName.equals(sdkSandboxPackageName)) {
                            return null;
                        }
                    }

                    final String sandboxProcessName =
                            this.mInjector
                                    .getSdkSandboxServiceProvider()
                                    .toSandboxProcessName(info.getCallingPackage());
                    final int sandboxUid = Process.toSdkSandboxUid(info.getCallingUid());

                    // Update process name and uid to match sandbox process for the calling app.
                    ActivityInfo activityInfo = info.getActivityInfo();
                    activityInfo.applicationInfo.uid = sandboxUid;
                    activityInfo.processName = sandboxProcessName;

                    return new ActivityInterceptorCallback.ActivityInterceptResult(
                            info.getIntent(), info.getCheckedOptions(), true);
                };
        ActivityInterceptorCallbackRegistry registry =
                ActivityInterceptorCallbackRegistry.getInstance();
        registry.registerActivityInterceptorCallback(
                MAINLINE_SDK_SANDBOX_ORDER_ID, mActivityInterceptorCallback);
    }

    private ArrayList<LoadSdkSession> getLoadedSdksForApp(CallingInfo callingInfo) {
        ArrayList<LoadSdkSession> loadedSdks = new ArrayList<>();
        synchronized (mLock) {
            if (mLoadSdkSessions.containsKey(callingInfo)) {
                ArrayList<LoadSdkSession> loadSessions =
                        new ArrayList<>(mLoadSdkSessions.get(callingInfo).values());
                for (int i = 0; i < loadSessions.size(); i++) {
                    LoadSdkSession sdk = loadSessions.get(i);
                    if (sdk.getStatus() == LoadSdkSession.LOADED) {
                        loadedSdks.add(sdk);
                    }
                }
            }
        }
        return loadedSdks;
    }

    @Override
    public void addSdkSandboxProcessDeathCallback(
            String callingPackageName,
            long timeAppCalledSystemServer,
            ISdkSandboxProcessDeathCallback callback) {
        final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

        final int callingUid = Binder.getCallingUid();
        final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__ADD_SDK_SANDBOX_LIFECYCLE_CALLBACK,
                /*latency=*/ (int)
                        (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                callingUid);

        synchronized (mLock) {
            if (mSandboxLifecycleCallbacks.containsKey(callingInfo)) {
                mSandboxLifecycleCallbacks.get(callingInfo).register(callback);
            } else {
                RemoteCallbackList<ISdkSandboxProcessDeathCallback> sandboxLifecycleCallbacks =
                        new RemoteCallbackList<>();
                sandboxLifecycleCallbacks.register(callback);
                mSandboxLifecycleCallbacks.put(callingInfo, sandboxLifecycleCallbacks);
            }
        }

        // addSdkSandboxProcessDeathCallback() can be called without calling loadSdk(). Register for
        // app death to make sure cleanup occurs.
        registerForAppDeath(callingInfo, callback.asBinder());

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__ADD_SDK_SANDBOX_LIFECYCLE_CALLBACK,
                /*latency=*/ (int)
                        (mInjector.getCurrentTime() - timeSystemServerReceivedCallFromApp),
                /*success=*/ true,
                SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                callingUid);
    }

    // Register a handler for app death using any binder object originating from the app. Returns
    // true if registering the handle succeeded and false if it failed (because the app died by
    // then).
    private boolean registerForAppDeath(CallingInfo callingInfo, IBinder appBinderObject) {
        // Register a death recipient to clean up app related state and unbind its service after
        // the app dies.
        try {
            synchronized (mLock) {
                if (!mCallingInfosWithDeathRecipients.containsKey(callingInfo)) {
                    Log.d(TAG, "Registering " + callingInfo + " for death notification");
                    appBinderObject.linkToDeath(() -> onAppDeath(callingInfo), 0);
                    mCallingInfosWithDeathRecipients.put(callingInfo, appBinderObject);
                }
            }
        } catch (RemoteException re) {
            // App has already died, cleanup sdk link, and unbind its service
            onAppDeath(callingInfo);
            return false;
        }

        return true;
    }

    @Override
    public void removeSdkSandboxProcessDeathCallback(
            String callingPackageName,
            long timeAppCalledSystemServer,
            ISdkSandboxProcessDeathCallback callback) {
        final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

        final int callingUid = Binder.getCallingUid();
        final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog
                        .SANDBOX_API_CALLED__METHOD__REMOVE_SDK_SANDBOX_LIFECYCLE_CALLBACK,
                /*latency=*/ (int)
                        (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                callingUid);

        synchronized (mLock) {
            RemoteCallbackList<ISdkSandboxProcessDeathCallback> sandboxLifecycleCallbacks =
                    mSandboxLifecycleCallbacks.get(callingInfo);
            if (sandboxLifecycleCallbacks != null) {
                sandboxLifecycleCallbacks.unregister(callback);
            }
        }
        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog
                        .SANDBOX_API_CALLED__METHOD__REMOVE_SDK_SANDBOX_LIFECYCLE_CALLBACK,
                /*latency=*/ (int)
                        (mInjector.getCurrentTime() - timeSystemServerReceivedCallFromApp),
                /*success=*/ true,
                SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                callingUid);
    }

    @Override
    public void loadSdk(
            String callingPackageName,
            IBinder callingAppProcessToken,
            String sdkName,
            long timeAppCalledSystemServer,
            Bundle params,
            ILoadSdkCallback callback) {
        try {
            // Log the IPC latency from app to system server
            final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

            final int callingUid = Binder.getCallingUid();
            final CallingInfo callingInfo =
                    CallingInfo.fromBinderWithApplicationThread(
                            mContext, callingPackageName, callingAppProcessToken);
            enforceCallerHasNetworkAccess(callingPackageName);
            enforceCallerOrItsSandboxRunInForeground(callingInfo);
            synchronized (mLock) {
                if (mRunningInstrumentations.contains(callingInfo)) {
                    throw new SecurityException(
                            "Currently running instrumentation of this sdk sandbox process");
                }
            }

            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__LOAD_SDK,
                    /*latency=*/ (int)
                            (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                    /*success=*/ true,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                    callingUid);

            final long token = Binder.clearCallingIdentity();
            try {
                loadSdkWithClearIdentity(
                        callingInfo,
                        sdkName,
                        params,
                        callback,
                        timeSystemServerReceivedCallFromApp);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (Throwable e) {
            try {
                Log.e(TAG, "Failed to load SDK " + sdkName, e);
                callback.onLoadSdkFailure(
                        new LoadSdkException(LOAD_SDK_INTERNAL_ERROR, e.getMessage(), e),
                        System.currentTimeMillis());
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to send onLoadCodeFailure", e);
            }
        }
    }

    private void loadSdkWithClearIdentity(
            CallingInfo callingInfo,
            String sdkName,
            Bundle params,
            ILoadSdkCallback callback,
            long timeSystemServerReceivedCallFromApp) {
        LoadSdkSession loadSdkSession =
                new LoadSdkSession(
                        mContext, this, mInjector, sdkName, callingInfo, params, callback);

        // SDK provider was invalid. This load request should fail.
        String errorMsg = loadSdkSession.getSdkProviderErrorIfExists();
        if (!TextUtils.isEmpty(errorMsg)) {
            Log.w(TAG, errorMsg);
            loadSdkSession.handleLoadFailure(
                    new LoadSdkException(SdkSandboxManager.LOAD_SDK_NOT_FOUND, errorMsg),
                    /*startTimeOfErrorStage=*/ timeSystemServerReceivedCallFromApp,
                    SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                    /*successAtStage=*/ false);
            return;
        }

        // Ensure we are not already loading this sdk. That's determined by checking if we already
        // have a completed LoadSdkSession with the same SDK name for the calling info.
        synchronized (mLock) {
            LoadSdkSession prevLoadSession = null;
            // Get any previous load session for this SDK if exists.
            if (mLoadSdkSessions.containsKey(callingInfo)) {
                prevLoadSession = mLoadSdkSessions.get(callingInfo).get(sdkName);
            }

            // If there was a previous load session and the status is loaded, this new load request
            // should fail.
            if (prevLoadSession != null && prevLoadSession.getStatus() == LoadSdkSession.LOADED) {
                loadSdkSession.handleLoadFailure(
                        new LoadSdkException(
                                SdkSandboxManager.LOAD_SDK_ALREADY_LOADED,
                                sdkName + " has been loaded already"),
                        /*startTimeOfErrorStage=*/ timeSystemServerReceivedCallFromApp,
                        SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                        /*successAtStage=*/ false);
                return;
            }

            // If there was an ongoing load session for this SDK, this new load request should fail.
            if (prevLoadSession != null
                    && prevLoadSession.getStatus() == LoadSdkSession.LOAD_PENDING) {
                loadSdkSession.handleLoadFailure(
                        new LoadSdkException(
                                SdkSandboxManager.LOAD_SDK_ALREADY_LOADED,
                                sdkName + " is currently being loaded"),
                        /*startTimeOfErrorStage=*/ timeSystemServerReceivedCallFromApp,
                        SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                        /*successAtStage=*/ false);
                return;
            }

            // If there was no previous load session (or there was one but its load status was
            // unloaded or failed), it should be replaced by the new load session.
            mLoadSdkSessions.computeIfAbsent(callingInfo, k -> new ArrayMap<>());
            mLoadSdkSessions.get(callingInfo).put(sdkName, loadSdkSession);
        }

        synchronized (mLock) {
            mUidImportanceListener.startListening();
            if (!registerForAppDeath(callingInfo, callback.asBinder())) {
                // Log the time taken in System Server before the exception occurred
                SdkSandboxStatsLog.write(
                        SdkSandboxStatsLog.SANDBOX_API_CALLED,
                        SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__LOAD_SDK,
                        /*latency=*/ (int)
                                (mInjector.getCurrentTime() - timeSystemServerReceivedCallFromApp),
                        /*success=*/ false,
                        SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                        callingInfo.getUid());
                // App has already died and there is no point in loading the SDK.
                return;
            }
        }

        // Callback to be invoked once the sandbox has been created;
        SandboxBindingCallback sandboxBindingCallback =
                createSdkLoadCallback(loadSdkSession, timeSystemServerReceivedCallFromApp);
        startSdkSandboxIfNeeded(callingInfo, sandboxBindingCallback);
    }

    private SandboxBindingCallback createSdkLoadCallback(
            LoadSdkSession loadSdkSession, long timeSystemServerReceivedCallFromApp) {
        return new SandboxBindingCallback() {
            @Override
            public void onBindingSuccessful(ISdkSandboxService service, int timeToLoadSandbox) {
                loadSdkForService(
                        loadSdkSession,
                        timeToLoadSandbox,
                        timeSystemServerReceivedCallFromApp,
                        service);
            }

            @Override
            public void onBindingFailed(
                    LoadSdkException exception, long startTimeForLoadingSandbox) {
                loadSdkSession.handleLoadFailure(
                        exception,
                        /*startTimeOfErrorStage=*/ startTimeForLoadingSandbox,
                        /*stage*/ SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__LOAD_SANDBOX,
                        /*successAtStage=*/ false);
            }
        };
    }

    void startSdkSandboxIfNeeded(CallingInfo callingInfo, SandboxBindingCallback callback) {

        boolean isSandboxStartRequired = false;
        synchronized (mLock) {
            @SdkSandboxServiceProvider.SandboxStatus
            int sandboxStatus = mServiceProvider.getSandboxStatusForApp(callingInfo);

            // Check if service is already created for the app.
            if (sandboxStatus == SdkSandboxServiceProvider.NON_EXISTENT
                    || sandboxStatus == SdkSandboxServiceProvider.CREATE_PENDING) {
                addSandboxBindingCallback(callingInfo, callback);
                if (sandboxStatus == SdkSandboxServiceProvider.NON_EXISTENT) {
                    isSandboxStartRequired = true;
                } else {
                    // The sandbox is in the process of being brought up. Nothing more to do here.
                    return;
                }
            }
        }

        final long startTimeForLoadingSandbox = mInjector.getCurrentTime();

        if (!isSandboxStartRequired) {
            ISdkSandboxService service = mServiceProvider.getSdkSandboxServiceForApp(callingInfo);
            if (service == null) {
                LoadSdkException exception =
                        new LoadSdkException(
                                SDK_SANDBOX_PROCESS_NOT_AVAILABLE, SANDBOX_NOT_AVAILABLE_MSG);
                callback.onBindingFailed(exception, startTimeForLoadingSandbox);
            }
            callback.onBindingSuccessful(service, -1);
            return;
        }

        // Prepare sdk data directories before starting the sandbox. If sdk data package directory
        // is missing, starting the sandbox process would crash as we will fail to mount data_mirror
        // for sdk-data isolation.
        mSdkSandboxStorageManager.prepareSdkDataOnLoad(callingInfo);
        mServiceProvider.bindService(
                callingInfo,
                new SandboxServiceConnection(
                        mServiceProvider, callingInfo, startTimeForLoadingSandbox));
    }

    private void addSandboxBindingCallback(
            CallingInfo callingInfo, SandboxBindingCallback callback) {
        synchronized (mLock) {
            mSandboxBindingCallbacks.computeIfAbsent(callingInfo, k -> new ArrayList<>());
            mSandboxBindingCallbacks.get(callingInfo).add(callback);
        }
    }

    @Override
    public void unloadSdk(
            String callingPackageName, String sdkName, long timeAppCalledSystemServer) {
        final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

        final int callingUid = Binder.getCallingUid();
        final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);
        enforceCallerOrItsSandboxRunInForeground(callingInfo);

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SANDBOX_API_CALLED__METHOD__UNLOAD_SDK,
                /*latency=*/ (int)
                        (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                callingUid);

        final long token = Binder.clearCallingIdentity();
        try {
            unloadSdkWithClearIdentity(callingInfo, sdkName, timeSystemServerReceivedCallFromApp);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void unloadSdkWithClearIdentity(
            CallingInfo callingInfo, String sdkName, long timeSystemServerReceivedCallFromApp) {
        LoadSdkSession prevLoadSession = null;
        long timeSystemServerReceivedCallFromSandbox;
        synchronized (mLock) {
            // TODO(b/254657226): Add a callback or return value for unloadSdk() to indicate
            // success of unload.

            // Get any previous load session for this SDK if exists.
            if (mLoadSdkSessions.containsKey(callingInfo)) {
                prevLoadSession = mLoadSdkSessions.get(callingInfo).get(sdkName);
            }
        }

        // If there was no previous load session or the SDK is not loaded, there is nothing to
        // unload.
        if (prevLoadSession == null) {
            // Unloading SDK that is not loaded is a no-op, return.
            Log.w(TAG, "SDK " + sdkName + " is not loaded for " + callingInfo);
            return;
        }

        prevLoadSession.unload(timeSystemServerReceivedCallFromApp);
        timeSystemServerReceivedCallFromSandbox = mInjector.getCurrentTime();

        ArrayList<LoadSdkSession> loadedSdks = getLoadedSdksForApp(callingInfo);
        if (loadedSdks.isEmpty()) {
            stopSdkSandboxService(
                    callingInfo, "Caller " + callingInfo + " has no remaining SDKS loaded.");
        }
        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SANDBOX_API_CALLED__METHOD__UNLOAD_SDK,
                (int) (mInjector.getCurrentTime() - timeSystemServerReceivedCallFromSandbox),
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_SANDBOX_TO_APP,
                callingInfo.getUid());
    }

    private void enforceCallingPackageBelongsToUid(CallingInfo callingInfo) {
        int callingUid = callingInfo.getUid();
        String callingPackage = callingInfo.getPackageName();
        int packageUid;
        PackageManager pm = mContext.createContextAsUser(
                UserHandle.getUserHandleForUid(callingUid), 0).getPackageManager();
        try {
            packageUid = pm.getPackageUid(callingPackage, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(callingPackage + " not found");
        }
        if (packageUid != callingUid) {
            throw new SecurityException(callingPackage + " does not belong to uid " + callingUid);
        }
    }

    private void enforceCallerOrItsSandboxRunInForeground(CallingInfo callingInfo) {
        String callingPackage = callingInfo.getPackageName();
        final long token = Binder.clearCallingIdentity();
        try {
            int importance =
                    Math.min(
                            mActivityManager.getUidImportance(callingInfo.getUid()),
                            mActivityManager.getUidImportance(
                                    Process.toSdkSandboxUid(callingInfo.getUid())));
            if (importance > IMPORTANCE_FOREGROUND) {
                throw new SecurityException(callingPackage + " does not run in the foreground");
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void enforceCallerHasNetworkAccess(String callingPackage) {
        mContext.enforceCallingPermission(android.Manifest.permission.INTERNET,
                callingPackage + " does not hold INTERNET permission");
        mContext.enforceCallingPermission(android.Manifest.permission.ACCESS_NETWORK_STATE,
                callingPackage + " does not hold ACCESS_NETWORK_STATE permission");
    }

    private void onAppDeath(CallingInfo callingInfo) {
        synchronized (mLock) {
            Log.d(TAG, "App " + callingInfo + " has died, cleaning up associated sandbox info");
            mSandboxLifecycleCallbacks.remove(callingInfo);
            mSandboxBindingCallbacks.remove(callingInfo);
            mCallingInfosWithDeathRecipients.remove(callingInfo);
            if (mCallingInfosWithDeathRecipients.size() == 0) {
                mUidImportanceListener.stopListening();
            }
            mSyncDataCallbacks.remove(callingInfo);
            mLoadSdkSessions.remove(callingInfo);
            stopSdkSandboxService(callingInfo, "Caller " + callingInfo + " has died");
            mServiceProvider.onAppDeath(callingInfo);
        }
    }

    @Override
    public void requestSurfacePackage(
            String callingPackageName,
            String sdkName,
            IBinder hostToken,
            int displayId,
            int width,
            int height,
            long timeAppCalledSystemServer,
            Bundle params,
            IRequestSurfacePackageCallback callback) {
        try {
            final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

            LogUtil.d(
                    TAG,
                    "requestSurfacePackage call received. callingPackageName: "
                            + callingPackageName);

            final int callingUid = Binder.getCallingUid();
            final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);
            enforceCallerOrItsSandboxRunInForeground(callingInfo);

            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    SANDBOX_API_CALLED__METHOD__REQUEST_SURFACE_PACKAGE,
                    /*latency=*/ (int)
                            (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                    /*success=*/ true,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                    callingUid);

            final long token = Binder.clearCallingIdentity();
            try {
                requestSurfacePackageWithClearIdentity(
                        callingInfo,
                        sdkName,
                        hostToken,
                        displayId,
                        width,
                        height,
                        timeSystemServerReceivedCallFromApp,
                        params,
                        callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (Throwable e) {
            try {
                callback.onSurfacePackageError(
                        IRequestSurfacePackageFromSdkCallback.SURFACE_PACKAGE_INTERNAL_ERROR,
                        e.getMessage(),
                        mInjector.getCurrentTime());
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to send onLoadCodeFailure", e);
            }
        }
    }

    private void requestSurfacePackageWithClearIdentity(
            CallingInfo callingInfo,
            String sdkName,
            IBinder hostToken,
            int displayId,
            int width,
            int height,
            long timeSystemServerReceivedCallFromApp,
            Bundle params,
            IRequestSurfacePackageCallback callback) {
        LoadSdkSession loadSdkSession = null;
        synchronized (mLock) {
            if (mLoadSdkSessions.containsKey(callingInfo)) {
                loadSdkSession = mLoadSdkSessions.get(callingInfo).get(sdkName);
            }
        }
        if (loadSdkSession == null) {
            LogUtil.d(
                    TAG,
                    callingInfo + " requested surface package, but could not find SDK " + sdkName);

            final long timeSystemServerCalledApp = mInjector.getCurrentTime();
            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__REQUEST_SURFACE_PACKAGE,
                    (int) (timeSystemServerCalledApp - timeSystemServerReceivedCallFromApp),
                    /*successAtStage*/ false,
                    SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                    callingInfo.getUid());

            try {
                callback.onSurfacePackageError(
                        REQUEST_SURFACE_PACKAGE_SDK_NOT_LOADED,
                        "SDK " + sdkName + " is not loaded",
                        timeSystemServerCalledApp);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to send onSurfacePackageError", e);
            }
            return;
        }

        loadSdkSession.requestSurfacePackage(
                hostToken,
                displayId,
                width,
                height,
                timeSystemServerReceivedCallFromApp,
                params,
                callback);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    void onUserUnlocking(int userId) {
        Log.i(TAG, "onUserUnlocking " + userId);
        // using postDelayed to wait for other volumes to mount
        mHandler.postDelayed(() -> mSdkSandboxStorageManager.onUserUnlocking(userId), 20000);
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingPermission(android.Manifest.permission.DUMP,
                "Can't dump " + TAG);

        if (args != null && args.length > 0 && args[0].equals(DUMP_ARG_AD_SERVICES)) {
            dumpAdServices(fd, writer, args, /* quiet= */ false);
            return;
        }

        // TODO(b/211575098): Use IndentingPrintWriter for better formatting
        synchronized (mLock) {
            writer.println("Checked Webview visibility patch exists: " + mCheckedVisibilityPatch);
            if (mCheckedVisibilityPatch) {
                writer.println("Build contains Webview visibility patch: " + mHasVisibilityPatch);
            }
            writer.println(
                    "Killswitch enabled: " + mSdkSandboxSettingsListener.isKillSwitchEnabled());
            writer.println(
                    "Customized Sdk Context enabled: "
                            + mSdkSandboxSettingsListener.isCustomizedSdkContextEnabled());
            writer.println("mLoadSdkSessions size: " + mLoadSdkSessions.size());
            for (CallingInfo callingInfo : mLoadSdkSessions.keySet()) {
                writer.printf("Caller: %s has following SDKs", callingInfo);
                writer.println();
                ArrayList<LoadSdkSession> loadSessions =
                        new ArrayList<>(mLoadSdkSessions.get(callingInfo).values());
                for (int i = 0; i < loadSessions.size(); i++) {
                    LoadSdkSession sdk = loadSessions.get(i);
                    writer.printf("SDK: %s Status: %s", sdk.mSdkName, sdk.getStatus());
                    writer.println();
                }
            }
            writer.println();

            writer.println("AdServicesManager binder published: " + mAdServicesManagerPublished);
        }

        writer.println("mServiceProvider:");
        mServiceProvider.dump(writer);
        writer.println();

        dumpAdServices(fd, writer, args, /* quiet= */ true);
    }

    private void dumpAdServices(
            @Nullable FileDescriptor fd, PrintWriter writer, String[] args, boolean quiet) {

        synchronized (mLock) {
            if (mAdServicesManagerPublished) {
                // AdServices registered itself as binder service
                if (quiet) {
                    Log.d(TAG, DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_AD_SERVICES_ITSELF);
                } else {
                    writer.println(DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_AD_SERVICES_ITSELF);
                }
                return;
            }
        }

        if (SdkLevel.isAtLeastU()) {
            // AdServices didn't register itself as binder service, but
            // AdServicesManagerService.Lifecycle implements Dumpable so it's dumped as
            // part of SystemServer
            if (quiet) {
                Log.d(TAG, DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_SYSTEM_SERVICE);
            } else {
                writer.println(DUMP_AD_SERVICES_MESSAGE_HANDLED_BY_SYSTEM_SERVICE);
            }
            return;
        }
        writer.print("AdServices:");
        IBinder adServicesManager = getAdServicesManager();
        if (adServicesManager == null) {
            // Should not happen on "real life", but it could on unit tests.
            Log.e(TAG, "dumpAdServices(): mAdServicesManager not set");
            writer.println(" N/A");
            return;
        }
        writer.println();
        writer.println();
        writer.flush(); // must flush, other raw dump on fd below will be printed before it
        try {
            adServicesManager.dump(fd, args);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to dump AdServices", e);
            // Shouldn't happen, but it doesn't hurt to catch
            writer.printf("Failed to dump Adservices: %s\n", e);
        }
        writer.println();
    }

    @Override
    public void syncDataFromClient(
            String callingPackageName,
            long timeAppCalledSystemServer,
            SharedPreferencesUpdate update,
            ISharedPreferencesSyncCallback callback) {
        try {
            final long timeSystemServerReceivedCallFromApp = mInjector.getCurrentTime();

            final int callingUid = Binder.getCallingUid();
            final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);
            enforceCallingPackageBelongsToUid(callingInfo);

            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__SYNC_DATA_FROM_CLIENT,
                    /*latency=*/ (int)
                            (timeSystemServerReceivedCallFromApp - timeAppCalledSystemServer),
                    /*success=*/ true,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__APP_TO_SYSTEM_SERVER,
                    callingUid);

            final long token = Binder.clearCallingIdentity();
            try {
                syncDataFromClientInternal(callingInfo, update, callback);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (Throwable e) {
            try {
                callback.onError(
                        ISharedPreferencesSyncCallback.PREFERENCES_SYNC_INTERNAL_ERROR,
                        e.getMessage());
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to send onLoadCodeFailure", e);
            }
        }
    }

    private void syncDataFromClientInternal(
            CallingInfo callingInfo,
            SharedPreferencesUpdate update,
            ISharedPreferencesSyncCallback callback) {
        // check first if service already bound
        ISdkSandboxService service = mServiceProvider.getSdkSandboxServiceForApp(callingInfo);
        if (service != null) {
            try {
                service.syncDataFromClient(update);
            } catch (RemoteException e) {
                syncDataOnError(callingInfo, callback, e.getMessage());
            }
        } else {
            syncDataOnError(callingInfo, callback, "Sandbox not available");
        }
    }

    private void syncDataOnError(
            CallingInfo callingInfo, ISharedPreferencesSyncCallback callback, String errorMsg) {
        // Store reference to the callback so that we can notify SdkSandboxManager when sandbox
        // starts
        synchronized (mLock) {
            mSyncDataCallbacks.put(callingInfo, callback);
        }
        try {
            callback.onError(ISharedPreferencesSyncCallback.SANDBOX_NOT_AVAILABLE, errorMsg);
        } catch (RemoteException ignore) {
            // App died. Sync will be re-established again by app later.
        }
    }

    @Override
    public void logLatencyFromSystemServerToApp(String method, int latency) {
        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                convertToStatsLogMethodCode(method),
                latency,
                /*success=*/ true,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_TO_APP,
                Binder.getCallingUid());
    }

    private int convertToStatsLogMethodCode(String method) {
        switch (method) {
            case ISdkSandboxManager.REQUEST_SURFACE_PACKAGE:
                return SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__REQUEST_SURFACE_PACKAGE;
            case ISdkSandboxManager.LOAD_SDK:
                return SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__LOAD_SDK;
            default:
                return SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__METHOD_UNSPECIFIED;
        }
    }

    interface SandboxBindingCallback {
        void onBindingSuccessful(ISdkSandboxService service, int timeToLoadSandbox);

        void onBindingFailed(LoadSdkException exception, long startTimeForLoadingSandbox);
    }

    class SandboxServiceConnection implements ServiceConnection {

        private final SdkSandboxServiceProvider mServiceProvider;
        private final CallingInfo mCallingInfo;
        private boolean mHasConnectedBefore = false;
        private long mStartTimeForLoadingSandbox;


        SandboxServiceConnection(
                SdkSandboxServiceProvider serviceProvider,
                CallingInfo callingInfo,
                long startTimeForLoadingSandbox) {
            mServiceProvider = serviceProvider;
            mCallingInfo = callingInfo;
            mStartTimeForLoadingSandbox = startTimeForLoadingSandbox;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final ISdkSandboxService mService = ISdkSandboxService.Stub.asInterface(service);

            // Perform actions needed after every sandbox restart.
            if (!onSandboxConnected(mService)) {
                // We don't need to call sandboxBindingCallback.onBindingFailed() in this case since
                // onSdkSandboxDeath() will take care of iterating through LoadSdkSessions and
                // informing SDKs about load failure.
                return;
            }

            // Set connected service for app once all initialization has finished. This needs to be
            // set after every sandbox restart as well.
            mServiceProvider.onServiceConnected(mCallingInfo, mService);

            // Once bound service has been set, sync manager is notified.
            notifySyncManagerSandboxStarted(mCallingInfo);

            BackgroundThread.getExecutor()
                    .execute(
                            () -> {
                                computeSdkStorage(mCallingInfo, mService);
                            });

            final int timeToLoadSandbox =
                    (int) (mInjector.getCurrentTime() - mStartTimeForLoadingSandbox);
            if (!mHasConnectedBefore) {
                logSandboxStart(timeToLoadSandbox);
                mHasConnectedBefore = true;
            }

            ArrayList<SandboxBindingCallback> sandboxBindingCallbacksForApp =
                    clearAndGetSandboxBindingCallbacks();
            for (int i = 0; i < sandboxBindingCallbacksForApp.size(); i++) {
                SandboxBindingCallback callback = sandboxBindingCallbacksForApp.get(i);
                callback.onBindingSuccessful(mService, timeToLoadSandbox);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Sdk sandbox crashed or killed, system will start it again.
            Log.d(TAG, "Sandbox service for " + mCallingInfo + " has been disconnected");
            mServiceProvider.onServiceDisconnected(mCallingInfo);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.d(TAG, "Sandbox service for " + mCallingInfo + " : died on binding");
            mServiceProvider.unbindService(mCallingInfo);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.d(TAG, "Sandbox service failed to bind for " + mCallingInfo + " : service is null");
            LoadSdkException exception =
                    new LoadSdkException(
                            SdkSandboxManager.LOAD_SDK_INTERNAL_ERROR,
                            "Failed to bind the service");
            ArrayList<SandboxBindingCallback> sandboxBindingCallbacksForApp =
                    clearAndGetSandboxBindingCallbacks();
            for (int i = 0; i < sandboxBindingCallbacksForApp.size(); i++) {
                SandboxBindingCallback callback = sandboxBindingCallbacksForApp.get(i);
                callback.onBindingFailed(exception, mStartTimeForLoadingSandbox);
            }
        }

        /**
         * Actions to be performed every time the sandbox connects for a particular app, such as the
         * first time the sandbox is brought up and every time it restarts.
         *
         * @return true if all actions were performed successfully, false otherwise.
         */
        private boolean onSandboxConnected(ISdkSandboxService service) {
            Log.i(
                    TAG,
                    String.format(
                            "Sdk sandbox has been bound for app package %s with uid %d",
                            mCallingInfo.getPackageName(), mCallingInfo.getUid()));
            try {
                service.asBinder().linkToDeath(() -> onSdkSandboxDeath(mCallingInfo), 0);
            } catch (RemoteException e) {
                // Sandbox had already died, cleanup sdk links, notify app etc.
                onSdkSandboxDeath(mCallingInfo);
                return false;
            }

            try {
                service.initialize(
                        new SdkToServiceLink(),
                        mSdkSandboxSettingsListener.isCustomizedSdkContextEnabled());
            } catch (Throwable e) {
                handleFailedSandboxInitialization(mCallingInfo);
                return false;
            }

            return true;
        }

        private void logSandboxStart(int timeToLoadSandbox) {
            // Log the latency for loading the Sandbox process
            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__LOAD_SDK,
                    timeToLoadSandbox,
                    /* success=*/ true,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__LOAD_SANDBOX,
                    mCallingInfo.getUid());
        }

        private ArrayList<SandboxBindingCallback> clearAndGetSandboxBindingCallbacks() {
            ArrayList<SandboxBindingCallback> sandboxBindingCallbacksForApp;
            synchronized (mLock) {
                sandboxBindingCallbacksForApp = mSandboxBindingCallbacks.get(mCallingInfo);
                mSandboxBindingCallbacks.remove(mCallingInfo);
            }
            if (sandboxBindingCallbacksForApp == null) {
                sandboxBindingCallbacksForApp = new ArrayList<>();
            }
            return sandboxBindingCallbacksForApp;
        }
    }

    void handleFailedSandboxInitialization(CallingInfo callingInfo) {
        final String errorMsg = "Failed to initialize sandbox";
        Log.e(TAG, errorMsg + " for " + callingInfo);
        // Kill the sandbox if it failed to initialize as it might not be properly usable.
        stopSdkSandboxService(callingInfo, errorMsg);
    }

    private void onSdkSandboxDeath(CallingInfo callingInfo) {
        synchronized (mLock) {
            killAppOnSandboxDeathIfNeededLocked(callingInfo);
            handleSandboxLifecycleCallbacksLocked(callingInfo);
            mSandboxBindingCallbacks.remove(callingInfo);
            mServiceProvider.onSandboxDeath(callingInfo);
            // All SDK state is lost on death.
            if (mLoadSdkSessions.containsKey(callingInfo)) {
                ArrayList<LoadSdkSession> loadSessions =
                        new ArrayList<>(mLoadSdkSessions.get(callingInfo).values());
                for (int i = 0; i < loadSessions.size(); i++) {
                    LoadSdkSession loadSdkSession = loadSessions.get(i);
                    loadSdkSession.onSandboxDeath();
                }
                mLoadSdkSessions.remove(callingInfo);
            }
        }
    }

    @GuardedBy("mLock")
    private void killAppOnSandboxDeathIfNeededLocked(CallingInfo callingInfo) {
        if (!SdkLevel.isAtLeastU()
                || !mCallingInfosWithDeathRecipients.containsKey(callingInfo)
                || mSandboxLifecycleCallbacks.containsKey(callingInfo)
                || getLoadedSdksForApp(callingInfo).size() == 0) {
            /* The app should not be killed in any one of the following cases:
               1) The SDK level is not U+ (as app kill API is not supported in that case).
               2) The app is already dead.
               3) The app has registered at least one callback to deal with sandbox death.
               4) The app has no SDKs loaded.
            */
            return;
        }

        // TODO(b/261442377): Only the processes that loaded some SDK should be killed. For now,
        // kill the process that loaded the first SDK.
        mActivityManagerLocal.killSdkSandboxClientAppProcess(callingInfo.getAppProcessToken());
    }

    @GuardedBy("mLock")
    private void handleSandboxLifecycleCallbacksLocked(CallingInfo callingInfo) {
        RemoteCallbackList<ISdkSandboxProcessDeathCallback> sandboxLifecycleCallbacks;
        sandboxLifecycleCallbacks = mSandboxLifecycleCallbacks.get(callingInfo);

        if (sandboxLifecycleCallbacks == null) {
            return;
        }

        int size = sandboxLifecycleCallbacks.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            try {
                sandboxLifecycleCallbacks.getBroadcastItem(i).onSdkSandboxDied();
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to send sdk sandbox death event to app", e);
            }
        }
        sandboxLifecycleCallbacks.finishBroadcast();
    }

    @Override
    public void stopSdkSandbox(String callingPackageName) {
        final CallingInfo callingInfo = CallingInfo.fromBinder(mContext, callingPackageName);

        mContext.enforceCallingPermission(
                STOP_SDK_SANDBOX_PERMISSION,
                callingPackageName + " does not have permission to stop their sandbox");

        final long token = Binder.clearCallingIdentity();
        try {
            stopSdkSandboxService(callingInfo, "App requesting sandbox kill");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public IBinder getAdServicesManager() {
        synchronized (mLock) {
            return mAdServicesManager;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    void registerAdServicesManagerService(IBinder iBinder, boolean published) {
        Log.d(TAG, "registerAdServicesManagerService(): published=" + published);
        synchronized (mLock) {
            mAdServicesManager = iBinder;
            mAdServicesManagerPublished = published;
        }
    }

    boolean isSdkSandboxDisabled(ISdkSandboxService boundService) {
        synchronized (mLock) {
            if (!mCheckedVisibilityPatch) {
                SdkSandboxDisabledCallback callback = new SdkSandboxDisabledCallback();
                try {
                    boundService.isDisabled(callback);
                    boolean isDisabled = callback.getIsDisabled();
                    mCheckedVisibilityPatch = true;
                    mHasVisibilityPatch = !isDisabled;
                } catch (Exception e) {
                    Log.w(TAG, "Could not verify SDK sandbox state", e);
                    return true;
                }
            }

            // Disable immediately if visibility patch is missing
            if (!mHasVisibilityPatch) {
                return true;
            }

            // Ignore killswitch if the device is an emulator
            if (mInjector.isEmulator()) {
                return false;
            }

            return getSdkSandboxSettingsListener().isKillSwitchEnabled();
        }
    }

    /**
     * Clears the SDK sandbox state. This will result in the state being checked again the next time
     * an SDK is loaded.
     */
    void clearSdkSandboxState() {
        synchronized (mLock) {
            mCheckedVisibilityPatch = DEFAULT_VALUE_VISIBILITY_PATCH_CHECKED;
            getSdkSandboxSettingsListener().setKillSwitchState(DEFAULT_VALUE_DISABLE_SDK_SANDBOX);
        }
    }

    /**
     * Enables the sandbox for testing purposes. Note that the sandbox can still be disabled by
     * setting the killswitch.
     */
    void forceEnableSandbox() {
        synchronized (mLock) {
            mCheckedVisibilityPatch = true;
            mHasVisibilityPatch = true;
            getSdkSandboxSettingsListener().setKillSwitchState(false);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    SdkSandboxSettingsListener getSdkSandboxSettingsListener() {
        synchronized (mLock) {
            return mSdkSandboxSettingsListener;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    void setSdkSandboxSettingsListener(SdkSandboxSettingsListener listener) {
        synchronized (mLock) {
            mSdkSandboxSettingsListener = listener;
        }
    }

    class SdkSandboxSettingsListener implements DeviceConfig.OnPropertiesChangedListener {

        private final Context mContext;
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private boolean mKillSwitchEnabled =
                DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        PROPERTY_DISABLE_SDK_SANDBOX,
                        DEFAULT_VALUE_DISABLE_SDK_SANDBOX);

        @GuardedBy("mLock")
        private boolean mEnforceBroadcastReceiverRestrictions =
                DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        PROPERTY_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS,
                        DEFAULT_VALUE_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS);

        @GuardedBy("mLock")
        private boolean mEnforceContentProviderRestrictions =
                DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        PROPERTY_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                        DEFAULT_VALUE_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS);

        SdkSandboxSettingsListener(Context context) {
            mContext = context;
        }

        private void registerObserver() {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_ADSERVICES, mContext.getMainExecutor(), this);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        boolean isKillSwitchEnabled() {
            synchronized (mLock) {
                return mKillSwitchEnabled;
            }
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        void setKillSwitchState(boolean enabled) {
            synchronized (mLock) {
                DeviceConfig.setProperty(
                        DeviceConfig.NAMESPACE_ADSERVICES,
                        PROPERTY_DISABLE_SDK_SANDBOX,
                        Boolean.toString(enabled),
                        false);
                mKillSwitchEnabled = enabled;
            }
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        void unregisterPropertiesListener() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        boolean isCustomizedSdkContextEnabled() {
            // Can only be enabled on U+ devices
            if (!SdkLevel.isAtLeastU()) {
                return false;
            }
            return DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ADSERVICES,
                    PROPERTY_CUSTOMIZED_SDK_CONTEXT_ENABLED,
                    DEFAULT_VALUE_CUSTOMIZED_SDK_CONTEXT_ENABLED);
        }

        boolean isBroadcastReceiverRestrictionsEnforced() {
            synchronized (mLock) {
                return mEnforceBroadcastReceiverRestrictions;
            }
        }

        boolean areContentProviderRestrictionsEnforced() {
            synchronized (mLock) {
                return mEnforceContentProviderRestrictions;
            }
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            synchronized (mLock) {
                if (!properties.getNamespace().equals(DeviceConfig.NAMESPACE_ADSERVICES)) {
                    return;
                }
                for (String name : properties.getKeyset()) {
                    if (name == null) {
                        continue;
                    }

                    switch (name) {
                        case PROPERTY_DISABLE_SDK_SANDBOX:
                            boolean killSwitchPreviouslyEnabled = mKillSwitchEnabled;
                            mKillSwitchEnabled =
                                    properties.getBoolean(
                                            PROPERTY_DISABLE_SDK_SANDBOX,
                                            DEFAULT_VALUE_DISABLE_SDK_SANDBOX);
                            if (mKillSwitchEnabled && !killSwitchPreviouslyEnabled) {
                                Log.i(TAG, "SDK sandbox killswitch has become enabled");
                                synchronized (SdkSandboxManagerService.this.mLock) {
                                    stopAllSandboxesLocked();
                                }
                            }
                            break;
                        case PROPERTY_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS:
                            mEnforceBroadcastReceiverRestrictions =
                                    properties.getBoolean(
                                            PROPERTY_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS,
                                            DEFAULT_VALUE_ENFORCE_BROADCAST_RECEIVER_RESTRICTIONS);
                            break;
                        case PROPERTY_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS:
                            mEnforceContentProviderRestrictions =
                                    properties.getBoolean(
                                            PROPERTY_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS,
                                            DEFAULT_VALUE_ENFORCE_CONTENT_PROVIDER_RESTRICTIONS);
                            break;
                        default:
                    }
                }
            }
        }
    }

    static class SdkSandboxDisabledCallback extends ISdkSandboxDisabledCallback.Stub {
        CountDownLatch mLatch;
        boolean mIsDisabled = false;

        SdkSandboxDisabledCallback() {
            mLatch = new CountDownLatch(1);
        }

        @Override
        public void onResult(boolean isDisabled) {
            mIsDisabled = isDisabled;
            mLatch.countDown();
        }

        boolean getIsDisabled() {
            try {
                if (mLatch.await(1, TimeUnit.SECONDS)) {
                    return mIsDisabled;
                }
                return true;
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for SDK sandbox state", e);
                return true;
            }
        }
    }

    /** Stops all running sandboxes in the case that the killswitch is triggered. */
    @GuardedBy("mLock")
    private void stopAllSandboxesLocked() {
        for (int i = mLoadSdkSessions.size() - 1; i >= 0; --i) {
            stopSdkSandboxService(mLoadSdkSessions.keyAt(i), "SDK sandbox killswitch enabled");
        }
    }

    void stopSdkSandboxService(CallingInfo currentCallingInfo, String reason) {
        if (!isSdkSandboxServiceRunning(currentCallingInfo)) {
            Log.d(TAG, "Cannot kill sandbox for " + currentCallingInfo + ", already dead");
            return;
        }

        mServiceProvider.unbindService(currentCallingInfo);

        // For T, we kill the sandbox by uid. For U, we kill a specific sandbox process.
        if (SdkLevel.isAtLeastU()) {
            mServiceProvider.stopSandboxService(currentCallingInfo);
        } else {
            // For apps with shared uid, unbind the sandboxes for all the remaining apps since we
            // kill the sandbox by uid.
            synchronized (mLock) {
                for (int i = 0; i < mCallingInfosWithDeathRecipients.size(); i++) {
                    final CallingInfo callingInfo = mCallingInfosWithDeathRecipients.keyAt(i);
                    if (callingInfo.getUid() == currentCallingInfo.getUid()) {
                        mServiceProvider.unbindService(callingInfo);
                    }
                }
            }
            final int sdkSandboxUid = Process.toSdkSandboxUid(currentCallingInfo.getUid());
            Log.i(TAG, "Killing sdk sandbox/s with uid " + sdkSandboxUid);
            mActivityManager.killUid(sdkSandboxUid, reason);
        }
    }

    boolean isSdkSandboxServiceRunning(CallingInfo callingInfo) {
        int sandboxStatus = mServiceProvider.getSandboxStatusForApp(callingInfo);
        return sandboxStatus == SdkSandboxServiceProvider.CREATED
                || sandboxStatus == SdkSandboxServiceProvider.CREATE_PENDING;
    }

    @WorkerThread
    private void computeSdkStorage(CallingInfo callingInfo, ISdkSandboxService service) {
        final List<StorageDirInfo> sharedStorageDirsInfo =
                mSdkSandboxStorageManager.getInternalStorageDirInfo(callingInfo);
        final List<StorageDirInfo> sdkStorageDirsInfo =
                mSdkSandboxStorageManager.getSdkStorageDirInfo(callingInfo);

        try {
            service.computeSdkStorage(
                    getListOfStoragePaths(sharedStorageDirsInfo),
                    getListOfStoragePaths(sdkStorageDirsInfo),
                    new IComputeSdkStorageCallback.Stub() {
                        @Override
                        public void onStorageInfoComputed(int sharedStorageKb, int sdkStorageKb) {
                            mSdkSandboxPulledAtoms.logStorage(
                                    callingInfo.getUid(), sharedStorageKb, sdkStorageKb);
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error while computing sdk storage for CallingInfo: " + callingInfo);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    List<String> getListOfStoragePaths(List<StorageDirInfo> storageDirInfos) {
        final List<String> paths = new ArrayList<>();

        for (int i = 0; i < storageDirInfos.size(); i++) {
            paths.add(storageDirInfos.get(i).getCeDataDir());
            paths.add(storageDirInfos.get(i).getDeDataDir());
        }
        return paths;
    }

    private void notifySyncManagerSandboxStarted(CallingInfo callingInfo) {
        ISharedPreferencesSyncCallback syncManagerCallback = null;
        synchronized (mLock) {
            syncManagerCallback = mSyncDataCallbacks.get(callingInfo);
            if (syncManagerCallback != null) {
                try {
                    syncManagerCallback.onSandboxStart();
                } catch (RemoteException ignore) {
                    // App died.
                }
            }
            mSyncDataCallbacks.remove(callingInfo);
        }
    }

    private void loadSdkForService(
            LoadSdkSession loadSdkSession,
            int timeToLoadSandbox,
            long timeSystemServerReceivedCallFromApp,
            ISdkSandboxService service) {
        CallingInfo callingInfo = loadSdkSession.mCallingInfo;

        if (isSdkSandboxDisabled(service)) {
            Log.e(TAG, "SDK cannot be loaded because SDK sandbox is disabled");
            loadSdkSession.handleLoadFailure(
                    new LoadSdkException(LOAD_SDK_SDK_SANDBOX_DISABLED, SANDBOX_DISABLED_MSG),
                    -1,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__METHOD_UNSPECIFIED,
                    false);
            return;
        }
        // Gather sdk storage information
        final StorageDirInfo sdkDataInfo =
                mSdkSandboxStorageManager.getSdkStorageDirInfo(
                        callingInfo, loadSdkSession.mSdkProviderInfo.getSdkInfo().getName());

        final long timeSystemServerCalledSandbox = mInjector.getCurrentTime();
        int latencySystemServerAppToSandbox =
                (int) (timeSystemServerCalledSandbox - timeSystemServerReceivedCallFromApp);
        if (timeToLoadSandbox != -1) {
            latencySystemServerAppToSandbox -= timeToLoadSandbox;
        }

        SdkSandboxStatsLog.write(
                SdkSandboxStatsLog.SANDBOX_API_CALLED,
                SdkSandboxStatsLog.SANDBOX_API_CALLED__METHOD__LOAD_SDK,
                latencySystemServerAppToSandbox,
                /*success=*/ true,
                SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_APP_TO_SANDBOX,
                callingInfo.getUid());

        loadSdkSession.load(
                service,
                sdkDataInfo.getCeDataDir(),
                sdkDataInfo.getDeDataDir(),
                timeSystemServerCalledSandbox,
                timeSystemServerReceivedCallFromApp);
    }

    private void failStartOrBindService(Intent intent) {
        throw new SecurityException(
                "SDK sandbox uid may not bind to or start to this service: " + intent.toString());
    }

    private void enforceAllowedToStartOrBindService(Intent intent) {
        ComponentName component = intent.getComponent();
        if (component == null) {
            failStartOrBindService(intent);
        }
        String componentPackageName = component.getPackageName();
        if (componentPackageName == null) {
            failStartOrBindService(intent);
        }
        if (componentPackageName.equals(WebViewUpdateService.getCurrentWebViewPackageName())
                || componentPackageName.equals(getAdServicesPackageName())) {
            return;
        }

        // Default disallow.
        failStartOrBindService(intent);
    }

    @Override
    public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out,
            ParcelFileDescriptor err, String[] args) {
        return mInjector
                .createShellCommand(this, mContext)
                .exec(
                        this,
                        in.getFileDescriptor(),
                        out.getFileDescriptor(),
                        err.getFileDescriptor(),
                        args);
    }

    private String resolveAdServicesPackage() {
        PackageManager pm = mContext.getPackageManager();
        Intent serviceIntent = new Intent(AdServicesCommon.ACTION_TOPICS_SERVICE);
        List<ResolveInfo> resolveInfos =
                pm.queryIntentServicesAsUser(
                        serviceIntent,
                        PackageManager.GET_SERVICES
                                | PackageManager.MATCH_SYSTEM_ONLY
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.SYSTEM);
        final ServiceInfo serviceInfo =
                AdServicesCommon.resolveAdServicesService(resolveInfos, serviceIntent.getAction());
        if (serviceInfo != null) {
            return serviceInfo.packageName;
        }
        return null;
    }

    @VisibleForTesting
    String getAdServicesPackageName() {
        return mAdServicesPackageName;
    }

    /**
     * A callback object to establish a link between the sdk in sandbox calling into manager
     * service.
     *
     * <p>When a sandbox is initialized, a callback object of {@link SdkToServiceLink} is passed to
     * be used as a part of {@link SdkSandboxController}. The Controller can then can call APIs on
     * the link object to get data from the manager service.
     */
    // TODO(b/268043836): Move SdkToServiceLink out of SdkSandboxManagerService
    private class SdkToServiceLink extends ISdkToServiceCallback.Stub {

        /**
         * Fetches {@link SandboxedSdk} for all SDKs that are loaded in the sandbox.
         *
         * <p>This provides the information on the library that is currently loaded in the sandbox
         * and also channels to communicate with loaded SDK.
         *
         * @param clientPackageName package name of the app for which the sdk was loaded in the
         *     sandbox
         * @return List of {@link SandboxedSdk} containing all currently loaded sdks
         */
        @Override
        public List<SandboxedSdk> getSandboxedSdks(String clientPackageName)
                throws RemoteException {
            // TODO(b/258195148): Write multiuser tests
            // TODO(b/242039497): Add authorisation checks to make sure only the sandbox calls this
            //  API.
            int uid = Binder.getCallingUid();
            if (Process.isSdkSandboxUid(uid)) {
                uid = Process.getAppUidForSdkSandboxUid(uid);
            }
            CallingInfo callingInfo = new CallingInfo(uid, clientPackageName);
            final List<SandboxedSdk> sandboxedSdks = new ArrayList<>();
            synchronized (mLock) {
                List<LoadSdkSession> loadedSdks = getLoadedSdksForApp(callingInfo);
                for (int i = 0; i < loadedSdks.size(); i++) {
                    LoadSdkSession sdk = loadedSdks.get(i);
                    SandboxedSdk sandboxedSdk = sdk.getSandboxedSdk();
                    if (sandboxedSdk != null) {
                        sandboxedSdks.add(sandboxedSdk);
                    } else {
                        Log.e(
                                TAG,
                                "SandboxedSdk is null for SDK "
                                        + sdk.mSdkName
                                        + " despite being loaded");
                    }
                }
            }
            return sandboxedSdks;
        }

        @Override
        public void logLatenciesFromSandbox(
                int latencyFromSystemServerToSandboxMillis,
                int latencySandboxMillis,
                int method,
                boolean success) {
            final int appUid = Process.getAppUidForSdkSandboxUid(Binder.getCallingUid());
            /**
             * In case system server is not involved and the API call is just concerned with sandbox
             * process, there will be no call to system server, and we will not log that information
             */
            if (latencyFromSystemServerToSandboxMillis != -1) {
                SdkSandboxStatsLog.write(
                        SdkSandboxStatsLog.SANDBOX_API_CALLED,
                        method,
                        latencyFromSystemServerToSandboxMillis,
                        success,
                        SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__SYSTEM_SERVER_TO_SANDBOX,
                        appUid);
            }
            SdkSandboxStatsLog.write(
                    SdkSandboxStatsLog.SANDBOX_API_CALLED,
                    method,
                    latencySandboxMillis,
                    /*success=*/ true,
                    SdkSandboxStatsLog.SANDBOX_API_CALLED__STAGE__SANDBOX,
                    appUid);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    SdkSandboxManagerLocal getLocalManager() {
        return mInjector.getLocalManager();
    }

    private void notifyInstrumentationStarted(CallingInfo callingInfo) {
        Log.d(TAG, "notifyInstrumentationStarted: clientApp = " + callingInfo.getPackageName()
                + " clientAppUid = " + callingInfo.getUid());
        synchronized (mLock) {
            mServiceProvider.unbindService(callingInfo);
            int sdkSandboxUid = Process.toSdkSandboxUid(callingInfo.getUid());
            mActivityManager.killUid(sdkSandboxUid, "instrumentation started");
            mRunningInstrumentations.add(callingInfo);
        }
        // TODO(b/223386213): we need to check if there is reconcileSdkData task already enqueued
        //  because the instrumented client app was just installed.
        mSdkSandboxStorageManager.notifyInstrumentationStarted(callingInfo);
    }

    private void notifyInstrumentationFinished(CallingInfo callingInfo) {
        Log.d(TAG, "notifyInstrumentationFinished: clientApp = " + callingInfo.getPackageName()
                + " clientAppUid = " + callingInfo.getUid());
        synchronized (mLock) {
            mRunningInstrumentations.remove(callingInfo);
        }
    }

    private boolean isInstrumentationRunning(CallingInfo callingInfo) {
        synchronized (mLock) {
            return mRunningInstrumentations.contains(callingInfo);
        }
    }

    /** @hide */
    public static class Lifecycle extends SystemService {
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        SdkSandboxManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new SdkSandboxManagerService(getContext());
        }

        @Override
        public void onStart() {
            publishBinderService(SDK_SANDBOX_SERVICE, mService);

            LocalManagerRegistry.addManager(
                    SdkSandboxManagerLocal.class, mService.getLocalManager());
        }

        @Override
        public void onUserUnlocking(TargetUser user) {
            final int userId = user.getUserHandle().getIdentifier();
            mService.onUserUnlocking(userId);
        }
    }

    private class UidImportanceListener implements ActivityManager.OnUidImportanceListener {

        private static final int IMPORTANCE_CUTPOINT = IMPORTANCE_VISIBLE;

        public boolean isListening = false;

        public void startListening() {
            synchronized (mLock) {
                if (isListening) {
                    return;
                }
                mActivityManager.addOnUidImportanceListener(this, IMPORTANCE_CUTPOINT);
                isListening = true;
            }
        }

        public void stopListening() {
            synchronized (mLock) {
                if (!isListening) {
                    return;
                }
                mActivityManager.removeOnUidImportanceListener(this);
                isListening = false;
            }
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (importance <= IMPORTANCE_CUTPOINT) {
                // The lower the importance value, the more "important" the process is. We
                // are only interested when the process is no longer in the foreground.
                return;
            }
            if (SdkLevel.isAtLeastU()) {
                // On U+, the priority of the sandbox is matched with the calling app, no need to
                // unbind.
                return;
            }
            synchronized (mLock) {
                for (int i = 0; i < mCallingInfosWithDeathRecipients.size(); i++) {
                    final CallingInfo callingInfo = mCallingInfosWithDeathRecipients.keyAt(i);
                    if (callingInfo.getUid() == uid) {
                        LogUtil.d(
                                TAG,
                                "App with uid "
                                        + uid
                                        + " has gone to the background, unbinding sandbox");
                        // Unbind the sandbox when the app goes to the background to lower its
                        // priority.
                        mServiceProvider.unbindService(callingInfo);
                    }
                }
            }
        }
    }

    // For testing as SANDBOXED_ACTIVITY_HANDLER_KEY is hidden from
    // SdkSandboxManagerServiceUnitTests
    @NonNull
    public String getSandboxedActivityHandlerKey() {
        return EXTRA_SANDBOXED_ACTIVITY_HANDLER;
    }

    private class LocalImpl implements SdkSandboxManagerLocal {
        @Override
        public void registerAdServicesManagerService(IBinder iBinder, boolean published) {
            SdkSandboxManagerService.this.registerAdServicesManagerService(iBinder, published);
        }

        @NonNull
        @Override
        public String getSdkSandboxProcessNameForInstrumentation(
                @NonNull ApplicationInfo clientAppInfo) {
            return clientAppInfo.processName + "_sdk_sandbox_instr";
        }

        @Override
        public void notifyInstrumentationStarted(
                @NonNull String clientAppPackageName, int clientAppUid) {
            SdkSandboxManagerService.this.notifyInstrumentationStarted(
                    new CallingInfo(clientAppUid, clientAppPackageName));
        }

        @Override
        public void notifyInstrumentationFinished(
                @NonNull String clientAppPackageName, int clientAppUid) {
            SdkSandboxManagerService.this.notifyInstrumentationFinished(
                    new CallingInfo(clientAppUid, clientAppPackageName));
        }

        @Override
        public boolean isInstrumentationRunning(
                @NonNull String clientAppPackageName, int clientAppUid) {
            return SdkSandboxManagerService.this.isInstrumentationRunning(
                    new CallingInfo(clientAppUid, clientAppPackageName));
        }

        @Override
        public void enforceAllowedToSendBroadcast(@NonNull Intent intent) {
            if (!canSendBroadcast(intent)) {
                throw new SecurityException(
                        "Intent "
                                + intent.getAction()
                                + " may not be broadcast from an SDK sandbox uid");
            }
        }

        @Override
        public boolean canSendBroadcast(@NonNull Intent intent) {
            return false;
        }

        @Override
        public void enforceAllowedToStartActivity(@NonNull Intent intent) {
            if (intent.getAction() != null) {
                if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
                    throw new SecurityException(
                            "Intent "
                                    + intent.getAction()
                                    + " may not be started from an SDK sandbox uid.");
                }
            }
        }

        @Override
        public void enforceAllowedToStartOrBindService(@NonNull Intent intent) {
            SdkSandboxManagerService.this.enforceAllowedToStartOrBindService(intent);
        }

        @Override
        public boolean canAccessContentProviderFromSdkSandbox(@NonNull ProviderInfo providerInfo) {
            // TODO(b/229200204): Implement a starter set of restrictions
            if (!Process.isSdkSandboxUid(Binder.getCallingUid())) {
                return true;
            }

            /**
             * By clearing the calling identity, system server identity is set which allows us to
             * call {@DeviceConfig.getBoolean}
             */
            final long token = Binder.clearCallingIdentity();

            try {
                return !mSdkSandboxSettingsListener.areContentProviderRestrictionsEnforced();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void enforceAllowedToHostSandboxedActivity(
                @NonNull Intent intent, int clientAppUid, @NonNull String clientAppPackageName) {
            if (Process.isSdkSandboxUid(clientAppUid)) {
                throw new SecurityException(
                        "Sandbox process is not allowed to start sandbox activities.");
            }
            if (intent == null) {
                throw new SecurityException("Intent to start sandbox activity is null.");
            }
            if (intent.getAction() == null
                    || !intent.getAction().equals(ACTION_START_SANDBOXED_ACTIVITY)) {
                throw new SecurityException(
                        "Sandbox activity intent must have an action ("
                                + ACTION_START_SANDBOXED_ACTIVITY
                                + ").");
            }
            String sandboxPackageName = mContext.getPackageManager().getSdkSandboxPackageName();
            if (intent.getPackage() == null || !intent.getPackage().equals(sandboxPackageName)) {
                throw new SecurityException(
                        "Sandbox activity intent's package must be set to the sandbox package");
            }
            if (intent.getComponent() != null) {
                final String componentPackageName = intent.getComponent().getPackageName();
                if (!componentPackageName.equals(sandboxPackageName)) {
                    throw new SecurityException(
                            "Sandbox activity intent's component must refer to the sandbox"
                                    + " package");
                }
            }
            final CallingInfo callingInfo = new CallingInfo(clientAppUid, clientAppPackageName);
            if (mServiceProvider.getSdkSandboxServiceForApp(callingInfo) == null) {
                throw new SecurityException(
                        "There is no sandbox process running for the caller uid"
                                + ": "
                                + clientAppUid
                                + ".");
            }

            Bundle extras = intent.getExtras();
            if (extras == null || extras.getBinder(getSandboxedActivityHandlerKey()) == null) {
                throw new IllegalArgumentException(
                        "Intent should contain an extra params with key = "
                                + getSandboxedActivityHandlerKey()
                                + " and value is an IBinder that identifies a registered "
                                + "SandboxedActivityHandler.");
            }
        }

        @Override
        // TODO(b/265647873): Add enforcement around onlyProtectedBroadcasts parameter
        public boolean canRegisterBroadcastReceiver(
                @NonNull IntentFilter intentFilter, int flags, boolean onlyProtectedBroadcasts) {
            if (!Process.isSdkSandboxUid(Binder.getCallingUid())) {
                return true;
            }

            if (intentFilter.countActions() == 0) {
                return false;
            }

            /**
             * By clearing the calling identity, system server identity is set which allows us to
             * call {@DeviceConfig.getBoolean}
             */
            final long token = Binder.clearCallingIdentity();

            try {
                final boolean enforceRestrictions =
                        mSdkSandboxSettingsListener.isBroadcastReceiverRestrictionsEnforced();
                final boolean exported = (flags & Context.RECEIVER_NOT_EXPORTED) == 0;
                return !enforceRestrictions || !exported;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
