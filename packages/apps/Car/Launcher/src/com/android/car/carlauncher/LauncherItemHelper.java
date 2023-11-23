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

import com.android.car.carlauncher.LauncherItemProto.LauncherItemListMessage;
import com.android.car.carlauncher.LauncherItemProto.LauncherItemMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Helper class that provides method used by LauncherModel
 */
public class LauncherItemHelper {
    private static final String TAG = "LauncherItemHelper";

    /**
     * This method is used to convert a list of launcher items into protobuf class
     */
    public LauncherItemListMessage launcherList2Msg(List<LauncherItem> launcherItemList) {
        List<LauncherItemMessage> msgList = new ArrayList<LauncherItemMessage>();
        if (launcherItemList == null) {
            return null;
        } else {
            for (int i = 0; i < launcherItemList.size(); i++) {
                msgList.add(launcherItemList.get(i).launcherItem2Msg(i, -1));
            }
        }
        LauncherItemListMessage.Builder builder =
                LauncherItemListMessage.newBuilder().addAllLauncherItemMessage(msgList);
        return builder.build();
    }

    /**
     * This method converts sort the LauncherItemList based on their
     * relative order in the proto file
     */
    public List<LauncherItemMessage> sortLauncherItemListMsg(
            LauncherItemListMessage launcherItemListMsg) {
        List<LauncherItemMessage> itemListMsg = launcherItemListMsg.getLauncherItemMessageList();
        List<LauncherItemMessage> items = new ArrayList<>();
        if (!itemListMsg.isEmpty() && itemListMsg.size() > 0) {
            //Need to create a new list for sorting purposes since ProtobufArrayList is not mutable
            items.addAll(itemListMsg);
            Collections.sort(items,
                    Comparator.comparingInt(LauncherItemMessage::getRelativePosition));
        }
        return items;
    }
}

