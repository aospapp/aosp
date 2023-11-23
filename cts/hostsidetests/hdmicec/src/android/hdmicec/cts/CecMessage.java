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

package android.hdmicec.cts;

import java.util.HashMap;
import java.util.Map;

public enum CecMessage {
    FEATURE_ABORT(0x00),
    TEXT_VIEW_ON(0x0d),
    SET_MENU_LANGUAGE(0x32),
    STANDBY(0x36),
    USER_CONTROL_PRESSED(0x44),
    USER_CONTROL_RELEASED(0x45),
    GIVE_OSD_NAME(0x46),
    SET_OSD_NAME(0x47),
    SYSTEM_AUDIO_MODE_REQUEST(0x70),
    SET_SYSTEM_AUDIO_MODE(0x72),
    GIVE_SYSTEM_AUDIO_MODE_STATUS(0x7d),
    ACTIVE_SOURCE(0x82),
    GIVE_PHYSICAL_ADDRESS(0x83),
    REPORT_PHYSICAL_ADDRESS(0x84),
    REQUEST_ACTIVE_SOURCE(0x85),
    SET_STREAM_PATH(0x86),
    DEVICE_VENDOR_ID(0x87),
    GIVE_DEVICE_VENDOR_ID(0x8c),
    GIVE_POWER_STATUS(0x8f),
    REPORT_POWER_STATUS(0x90),
    GET_MENU_LANGUAGE(0x91),
    INACTIVE_SOURCE(0x9d),
    CEC_VERSION(0x9e),
    GET_CEC_VERSION(0x9f),
    ABORT(0xff);

    private final int messageId;
    private static Map messageMap = new HashMap<>();

    static {
        for (CecMessage message : CecMessage.values()) {
            messageMap.put(message.messageId, message);
        }
    }

    public static CecMessage getMessage(int messageId) {
        return (CecMessage) messageMap.get(messageId);
    }

    @Override
    public String toString() {
        return String.format("%02x", messageId);
    }

    private CecMessage(int messageId) {
        this.messageId = messageId;
    }

    public static String formatParams(long rawParam) {
        StringBuilder params = new StringBuilder("");

        do {
            params.insert(0, ":" + String.format("%02x", rawParam % 256));
            rawParam >>= 8;
        } while (rawParam > 0);

        return params.toString();
    }
}
