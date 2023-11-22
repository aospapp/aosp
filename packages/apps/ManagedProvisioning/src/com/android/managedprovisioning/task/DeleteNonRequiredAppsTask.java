/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.managedprovisioning.task;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.managedprovisioning.ProvisionLogger;
import com.android.managedprovisioning.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/**
 * Removes all system apps with a launcher that are not required.
 * Also disables sharing via Bluetooth and Nfc, and components that listen to
 * ACTION_INSTALL_SHORTCUT.
 * This class is called a first time when a user is created, but also after a system update.
 * In this case, it checks if the system apps that have been added need to be disabled.
 */
public class DeleteNonRequiredAppsTask {
    private final Callback mCallback;
    private final Context mContext;
    private final IPackageManager mIpm;
    private final String mMdmPackageName;
    private final PackageManager mPm;
    private final int mReqAppsList;
    private final int mVendorReqAppsList;
    private final int mUserId;
    private final boolean mNewProfile; // If we are provisioning a new profile.
    private final boolean mDisableInstallShortcutListeners;

    private static final String TAG_SYSTEM_APPS = "system-apps";
    private static final String TAG_PACKAGE_LIST_ITEM = "item";
    private static final String ATTR_VALUE = "value";

    public DeleteNonRequiredAppsTask(Context context, String mdmPackageName, int userId,
            int requiredAppsList, int vendorRequiredAppsList, boolean newProfile,
            boolean disableInstallShortcutListeners, Callback callback) {
        mCallback = callback;
        mContext = context;
        mMdmPackageName = mdmPackageName;
        mUserId = userId;
        mIpm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mPm = context.getPackageManager();
        mReqAppsList = requiredAppsList;
        mVendorReqAppsList = vendorRequiredAppsList;
        mNewProfile = newProfile;
        mDisableInstallShortcutListeners = disableInstallShortcutListeners;
    }

    public void run() {
        if (mNewProfile) {
            disableNfcBluetoothSharing();
        }
        deleteNonRequiredApps();
    }

    private void disableNfcBluetoothSharing() {
        ProvisionLogger.logd("Disabling Nfc and Bluetooth sharing.");
        disableComponent(new ComponentName("com.android.nfc", "com.android.nfc.BeamShareActivity"));
        disableComponent(new ComponentName("com.android.bluetooth",
                "com.android.bluetooth.opp.BluetoothOppLauncherActivity"));
    }

    private void deleteNonRequiredApps() {
        ProvisionLogger.logd("Deleting non required apps.");

        File file = new File(mContext.getFilesDir() + File.separator + "system_apps"
                + File.separator + "user" + mUserId + ".xml");
        file.getParentFile().mkdirs(); // Creating the folder if it does not exist

        Set<String> currentApps = getCurrentSystemApps();
        Set<String> previousApps;
        if (mNewProfile) {
            // If this userId was a managed profile before, file may exist. In this case, we ignore
            // what is in this file.
            previousApps = new HashSet<String>();
        } else {
            if (file.exists()) {
                previousApps = readSystemApps(file);
            } else {
                // If for some reason, the system apps have not been written to file before, we will
                // not delete any system apps this time.
                writeSystemApps(currentApps, file);
                mCallback.onSuccess();
                return;
            }
        }
        writeSystemApps(currentApps, file);
        Set<String> newApps = currentApps;
        newApps.removeAll(previousApps);

        if (mDisableInstallShortcutListeners) {
            Intent actionShortcut = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            if (previousApps.isEmpty()) {
                // Here, all the apps are in newApps.
                // It is faster to do it this way than to go through all the apps one by one.
                disableReceivers(actionShortcut);
            } else {
                // Here, all the apps are not in newApps. So we have to go through all the new
                // apps one by one.
                for (String newApp : newApps) {
                    actionShortcut.setPackage(newApp);
                    disableReceivers(actionShortcut);
                }
            }
        }
        Set<String> packagesToDelete = newApps;
        packagesToDelete.removeAll(getRequiredApps());
        packagesToDelete.retainAll(getCurrentAppsWithLauncher());
        // com.android.server.telecom should not handle CALL intents in the managed profile.
        if (mNewProfile) {
            packagesToDelete.add("com.android.server.telecom");
        }
        int size = packagesToDelete.size();
        if (size > 0) {
            PackageDeleteObserver packageDeleteObserver =
                        new PackageDeleteObserver(packagesToDelete.size());
            for (String packageName : packagesToDelete) {
                try {
                    mIpm.deletePackageAsUser(packageName, packageDeleteObserver, mUserId,
                            PackageManager.DELETE_SYSTEM_APP);
                } catch (RemoteException neverThrown) {
                    // Never thrown, as we are making local calls.
                    ProvisionLogger.loge("This should not happen.", neverThrown);
                }
            }
        } else {
            mCallback.onSuccess();
        }
    }

    private void disableReceivers(Intent intent) {
        List<ResolveInfo> receivers = mPm.queryBroadcastReceivers(intent, 0, mUserId);
        for (ResolveInfo ri : receivers) {
            // One of ri.activityInfo, ri.serviceInfo, ri.providerInfo is not null. Let's find which
            // one.
            ComponentInfo ci;
            if (ri.activityInfo != null) {
                ci = ri.activityInfo;
            } else if (ri.serviceInfo != null) {
                ci = ri.serviceInfo;
            } else {
                ci = ri.providerInfo;
            }
            disableComponent(new ComponentName(ci.packageName, ci.name));
        }
    }

    private void disableComponent(ComponentName toDisable) {
        try {
            mIpm.setComponentEnabledSetting(toDisable,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
                    mUserId);
        } catch (RemoteException neverThrown) {
            ProvisionLogger.loge("This should not happen.", neverThrown);
        } catch (Exception e) {
            ProvisionLogger.logw("Component not found, not disabling it: "
                + toDisable.toShortString());
        }
    }

    /**
     * Returns the set of package names of apps that are in the system image,
     * whether they have been deleted or not.
     */
    private Set<String> getCurrentSystemApps() {
        Set<String> apps = new HashSet<String>();
        List<ApplicationInfo> aInfos = null;
        try {
            aInfos = mIpm.getInstalledApplications(
                    PackageManager.GET_UNINSTALLED_PACKAGES, mUserId).getList();
        } catch (RemoteException neverThrown) {
            // Never thrown, as we are making local calls.
            ProvisionLogger.loge("This should not happen.", neverThrown);
        }
        for (ApplicationInfo aInfo : aInfos) {
            if ((aInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                apps.add(aInfo.packageName);
            }
        }
        return apps;
    }

    private Set<String> getCurrentAppsWithLauncher() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = mPm.queryIntentActivitiesAsUser(launcherIntent,
                PackageManager.GET_UNINSTALLED_PACKAGES, mUserId);
        Set<String> apps = new HashSet<String>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            apps.add(resolveInfo.activityInfo.packageName);
        }
        return apps;
    }

    private void writeSystemApps(Set<String> packageNames, File file) {
        try {
            FileOutputStream stream = new FileOutputStream(file, false);
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, "utf-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_SYSTEM_APPS);
            for (String packageName : packageNames) {
                serializer.startTag(null, TAG_PACKAGE_LIST_ITEM);
                serializer.attribute(null, ATTR_VALUE, packageName);
                serializer.endTag(null, TAG_PACKAGE_LIST_ITEM);
            }
            serializer.endTag(null, TAG_SYSTEM_APPS);
            serializer.endDocument();
            stream.close();
        } catch (IOException e) {
            ProvisionLogger.loge("IOException trying to write the system apps", e);
        }
    }

    private Set<String> readSystemApps(File file) {
        Set<String> result = new HashSet<String>();
        if (!file.exists()) {
            return result;
        }
        try {
            FileInputStream stream = new FileInputStream(file);

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type = parser.next();
            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if (tag.equals(TAG_PACKAGE_LIST_ITEM)) {
                    result.add(parser.getAttributeValue(null, ATTR_VALUE));
                } else {
                    ProvisionLogger.loge("Unknown tag: " + tag);
                }
            }
            stream.close();
        } catch (IOException e) {
            ProvisionLogger.loge("IOException trying to read the system apps", e);
        } catch (XmlPullParserException e) {
            ProvisionLogger.loge("XmlPullParserException trying to read the system apps", e);
        }
        return result;
    }

    protected Set<String> getRequiredApps() {
        HashSet<String> requiredApps = new HashSet<String> (Arrays.asList(
                        mContext.getResources().getStringArray(mReqAppsList)));
        requiredApps.addAll(Arrays.asList(
                        mContext.getResources().getStringArray(mVendorReqAppsList)));
        requiredApps.add(mMdmPackageName);
        return requiredApps;
    }

    /**
     * Runs the next task when all packages have been deleted or shuts down the activity if package
     * deletion fails.
     */
    class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        private final AtomicInteger mPackageCount = new AtomicInteger(0);

        public PackageDeleteObserver(int packageCount) {
            this.mPackageCount.set(packageCount);
        }

        @Override
        public void packageDeleted(String packageName, int returnCode) {
            if (returnCode != PackageManager.DELETE_SUCCEEDED) {
                ProvisionLogger.logw(
                        "Could not finish the provisioning: package deletion failed");
                mCallback.onError();
            }
            int currentPackageCount = mPackageCount.decrementAndGet();
            if (currentPackageCount == 0) {
                ProvisionLogger.logi("All non-required system apps have been uninstalled.");
                mCallback.onSuccess();
            }
        }
    }

    public abstract static class Callback {
        public abstract void onSuccess();
        public abstract void onError();
    }
}
