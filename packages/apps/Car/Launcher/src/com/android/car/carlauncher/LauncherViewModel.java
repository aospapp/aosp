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

package com.android.car.carlauncher;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.android.car.carlauncher.LauncherItemProto.LauncherItemListMessage;
import com.android.car.carlauncher.LauncherItemProto.LauncherItemMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A launcher model decides how the apps are displayed.
 */
public class LauncherViewModel extends ViewModel {
    private static final String TAG = "LauncherModel";
    private boolean mIsCustomized;
    private boolean mIsAlphabetized;
    private boolean mAppOrderRead;
    public static final String ORDER_FILE_NAME = "order.data";
    private Map<ComponentName, LauncherItem> mLauncherItemMap = new HashMap<>();
    private final MutableLiveData<List<LauncherItem>> mCurrentLauncher =
            new MutableLiveData<>(new ArrayList<>());
    private List<LauncherItemMessage> mItemsFromProto = new ArrayList<>();
    private List<LauncherItem> mItemsFromPlatform = new ArrayList<>();
    private List<LauncherItem> mFinalItems = new ArrayList<>();
    private OutputStream mOutputStream;
    private InputStream mInputStream;
    private LauncherItemHelper mLauncherItemHelper;
    private File mFileDir;

    public LauncherViewModel(File fileDir) {
        mLauncherItemHelper = new LauncherItemHelper();
        mFileDir = fileDir;
    }

    public static final Comparator<LauncherItem> ALPHABETICAL_COMPARATOR = Comparator.comparing(
            LauncherItem::getDisplayName, String::compareToIgnoreCase);

    public boolean isCustomized() {
        return mIsCustomized;
    }

    public LiveData<List<LauncherItem>> getCurrentLauncher() {
        return mCurrentLauncher;
    }

    public Map<ComponentName, LauncherItem> getLauncherItemMap() {
        return mLauncherItemMap;
    }

    @VisibleForTesting
    OutputStream getOutputStream() {
        return mOutputStream;
    }

    @VisibleForTesting
    void setOutputStream(OutputStream outputStream) {
        mOutputStream = outputStream;
    }

    @VisibleForTesting
    void setInputStream(InputStream inputStream) {
        mInputStream = inputStream;
    }

    @VisibleForTesting
    void setLauncherItemHelper(LauncherItemHelper helper) {
        mLauncherItemHelper = helper;
    }

    /**
     * Populate the apps based on alphabetical order and create mapping from packageName to
     * LauncherItem. Each item in the current launcher is AppItem.
     */
    public void generateAlphabetizedAppOrder(AppLauncherUtils.LauncherAppsInfo launcherAppsInfo) {
        List<LauncherItem> tempList = new ArrayList<>();
        mLauncherItemMap.clear();
        List<AppMetaData> apps = launcherAppsInfo.getLaunchableComponentsList();
        for (AppMetaData app : apps) {
            LauncherItem launcherItem = new AppItem(app.getPackageName(), app.getClassName(),
                    app.getDisplayName(), app);
            tempList.add(launcherItem);
            mLauncherItemMap.put(app.getComponentName(), launcherItem);
        }
        Collections.sort(tempList, LauncherViewModel.ALPHABETICAL_COMPARATOR);
        mItemsFromPlatform = tempList;
        mIsAlphabetized = true;
        createAppList();
    }

    /**
     * Populate the current launcher in the correct order if there are any order
     * recorded and update the mapping
     */

    public void updateAppsOrder() {
        mItemsFromProto.clear();
        try {
            File order = new File(mFileDir, ORDER_FILE_NAME);
            if (order.exists()) {
                if (mInputStream == null) {
                    mInputStream = new FileInputStream(order);
                }
                LauncherItemListMessage launcherItemListMsg =
                        LauncherItemListMessage.parseDelimitedFrom(mInputStream);
                if (launcherItemListMsg != null
                        && launcherItemListMsg.getLauncherItemMessageCount() != 0) {
                    mIsCustomized = true;
                    mItemsFromProto = mLauncherItemHelper.sortLauncherItemListMsg(
                            launcherItemListMsg);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Read from input stream not successfully");
        } finally {
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                    mInputStream = null;
                } catch (IOException e) {
                    Log.e(TAG, "Unable to close input stream");
                }
            }
            mAppOrderRead = true;
            createAppList();
        }
    }

    private void createAppList() {
        Set<ComponentName> componentNames = new HashSet<>();
        if (mIsAlphabetized && mAppOrderRead) {
            mFinalItems.clear();
            if (!mItemsFromProto.isEmpty()) {
                for (LauncherItemMessage item : mItemsFromProto) {
                    LauncherItem itemFromMap = mLauncherItemMap.get(
                            new ComponentName(item.getPackageName(), item.getClassName()));
                    // If item exists in proto but not in map, (e.g, when app
                    // is disabled from Settings), it can be ignored
                    if (itemFromMap != null) {
                        mFinalItems.add(itemFromMap);
                        componentNames.add(new
                                ComponentName(itemFromMap.getPackageName(),
                                itemFromMap.getClassName()));
                    }
                }
                // If item exists in map but not in proto (e.g, when app
                // is enabled from Settings), app must be added to the current list
                List<ComponentName> componentNamesNotInProto = mLauncherItemMap.keySet()
                        .stream()
                        .filter(element -> !componentNames.contains(element))
                        .collect(Collectors.toList());
                if (!componentNamesNotInProto.isEmpty()) {
                    Collections.sort(componentNamesNotInProto);
                    for (ComponentName componentName: componentNamesNotInProto) {
                        mFinalItems.add(mLauncherItemMap.get(componentName));
                    }
                }
                mCurrentLauncher.postValue(mFinalItems);
            } else {
                mCurrentLauncher.postValue(mItemsFromPlatform);
            }
            mIsAlphabetized = false;
            mAppOrderRead = false;
        }
    }

    /**
     * Update an AppItem's AppMetaData isMirroring state and its launchCallback
     * Then, post the updated live data object
     */
    // TODO (b/272796126): refactor to data model and move deep copying to inside DiffUtil
    public void updateMirroringItem(String packageName, Intent mirroringIntent) {
        List<LauncherItem> launcherList = mCurrentLauncher.getValue();
        if (launcherList == null) {
            return;
        }
        List<LauncherItem> launcherListCopy = new ArrayList<>();
        for (LauncherItem item : launcherList) {
            if (item instanceof AppItem) {
                AppMetaData metaData = ((AppItem) item).getAppMetaData();
                if (item.getPackageName().equals(packageName)) {
                    launcherListCopy.add(new AppItem(item.getPackageName(), item.getClassName(),
                            item.getDisplayName(), new AppMetaData(metaData.getDisplayName(),
                            metaData.getComponentName(), metaData.getIcon(),
                            metaData.getIsDistractionOptimized(), /* isMirroring= */ true,
                            contextArg -> AppLauncherUtils.launchApp(contextArg, mirroringIntent),
                            metaData.getAlternateLaunchCallback())));
                } else if (metaData.getIsMirroring()) {
                    Intent intent = new Intent(Intent.ACTION_MAIN)
                            .setComponent(metaData.getComponentName())
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    launcherListCopy.add(new AppItem(item.getPackageName(), item.getClassName(),
                            item.getDisplayName(), new AppMetaData(metaData.getDisplayName(),
                            metaData.getComponentName(), metaData.getIcon(),
                            metaData.getIsDistractionOptimized(), /* isMirroring= */ false,
                            contextArg -> AppLauncherUtils.launchApp(contextArg, intent),
                            metaData.getAlternateLaunchCallback())));
                } else {
                    launcherListCopy.add(item);
                }
            } else {
                launcherListCopy.add(item);
            }
        }
        mCurrentLauncher.postValue(launcherListCopy);
    }

    /**
     * Record the current apps' order to a file if needed
     */
    public void maybeSaveAppsOrder() {
        if (isCustomized()) {
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            executorService.execute(() -> {
                writeToFile();
                executorService.shutdown();
            });
        }
    }

    protected void writeToFile() {
        LauncherItemListMessage launcherItemListMessage = mLauncherItemHelper.launcherList2Msg(
                mCurrentLauncher.getValue());
        try {
            if (mOutputStream == null) {
                mOutputStream = new FileOutputStream(new File(mFileDir, ORDER_FILE_NAME), false);
            }
            launcherItemListMessage.writeDelimitedTo(mOutputStream);
        } catch (IOException e) {
            Log.e(TAG, "Order not written to file successfully");
        } finally {
            try {
                if (mOutputStream != null) {
                    mOutputStream.flush();
                    if (mOutputStream instanceof FileOutputStream) {
                        ((FileOutputStream) mOutputStream).getFD().sync();
                    }
                    mOutputStream.close();
                    mOutputStream = null;
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to close output stream");
            }
        }
    }

    /**
     * Move an app to a specified index
     */
    public void movePackage(int index, AppMetaData app) {
        List<LauncherItem> current = mCurrentLauncher.getValue();
        LauncherItem item = mLauncherItemMap.get(app.getComponentName());
        if (current != null && current.size() != 0 && index < current.size() && item != null) {
            current.remove(item);
            current.add(index, item);
            mIsCustomized = true;
            mCurrentLauncher.postValue(current);
        }
    }

    /**
     * Add a new app to the current list
     */
    public void addPackage(AppMetaData app) {
        if (app != null && !mLauncherItemMap.containsKey(app.getComponentName())) {
            List<LauncherItem> current = mCurrentLauncher.getValue();
            LauncherItem launcherItem = new AppItem(app.getPackageName(), app.getClassName(),
                    app.getDisplayName(), app);
            current.add(launcherItem);
            mLauncherItemMap.put(app.getComponentName(), launcherItem);
            if (!mIsCustomized) {
                Collections.sort(current, LauncherViewModel.ALPHABETICAL_COMPARATOR);
            }
            mCurrentLauncher.postValue(current);
        }
    }

    /**
     * Remove an app from the current launcher
     */
    public void removePackage(AppMetaData app) {
        if (app != null && mLauncherItemMap.containsKey(app.getComponentName())) {
            List<LauncherItem> current = mCurrentLauncher.getValue();
            LauncherItem launcherItem = mLauncherItemMap.get(app.getComponentName());
            if (current != null && current.size() != 0) {
                current.remove(launcherItem);
                mCurrentLauncher.postValue(current);
                mLauncherItemMap.remove(app.getComponentName());
            }
        }
    }

    /**
     * Check if the order file exists
     */
    public boolean doesFileExist() {
        File order = new File(mFileDir, ORDER_FILE_NAME);
        return order.exists();
    }

    public void setCustomized(boolean customized) {
        mIsCustomized = customized;
    }

    public void setAppOrderRead(boolean appOrderRead) {
        mAppOrderRead = appOrderRead;
    }
}
