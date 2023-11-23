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

package com.android.car.telemetry.publisher.statsconverters;

import android.util.SparseArray;

import com.android.car.telemetry.AtomsProto.ActivityForegroundStateChanged;
import com.android.car.telemetry.AtomsProto.Atom;

/**
 * Atom data converter for atoms of type {@link ActivityForegroundStateChanged}.
 */
public class ActivityForegroundStateChangedConverter
        extends AbstractAtomConverter<ActivityForegroundStateChanged> {
    private static final SparseArray<
            AtomFieldAccessor<ActivityForegroundStateChanged>> sAtomFieldAccessorMap =
            new SparseArray<>();
    static {
        sAtomFieldAccessorMap.append(1, new AtomFieldAccessor<>(
                "uid",
                a -> a.hasUid(),
                a -> a.getUid()
        ));
        sAtomFieldAccessorMap.append(2, new AtomFieldAccessor<>(
                "pkg_name",
                a -> a.hasPkgName(),
                a -> a.getPkgName()
        ));
        sAtomFieldAccessorMap.append(3, new AtomFieldAccessor<>(
                "class_name",
                a -> a.hasClassName(),
                a -> a.getClassName()
        ));
        sAtomFieldAccessorMap.append(4, new AtomFieldAccessor<>(
                "state",
                a -> a.hasState(),
                a -> a.getState().getNumber()
        ));
    }

    ActivityForegroundStateChangedConverter() {
        super();
    }

    @Override
    SparseArray<AtomFieldAccessor<ActivityForegroundStateChanged>> getAtomFieldAccessorMap() {
        return sAtomFieldAccessorMap;
    }

    @Override
    ActivityForegroundStateChanged getAtomData(Atom atom) {
        return atom.getActivityForegroundStateChanged();
    }

    @Override
    String getAtomDataClassName() {
        return ActivityForegroundStateChanged.class.getSimpleName();
    }
}
