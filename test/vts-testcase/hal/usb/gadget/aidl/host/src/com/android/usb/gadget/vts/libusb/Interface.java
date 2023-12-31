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

package com.android.tests.usbgadget.libusb;

import com.google.common.collect.ImmutableList;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.List;

public class Interface extends Structure {
    public static class ByReference extends Interface implements Structure.ByReference {}

    public Interface() {}

    public Interface(Pointer p) {
        super(p);
        read();
    }

    public Interface[] toArray(int size) {
        return (Interface[]) super.toArray(new Interface[size]);
    }

    @Override
    protected List<String> getFieldOrder() {
        return ImmutableList.of("altsetting", "num_altsetting");
    }

    public InterfaceDescriptor.ByReference altsetting;
    public int num_altsetting;
}
