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

import com.google.protobuf.MessageLite;

import java.util.function.Function;

/**
 * Class that contains metadata and actions for a field of atom data type T.
 *
 * @param <T> the atom data type.
 */
public class AtomFieldAccessor<T extends MessageLite> {
    private final String mFieldName;
    private final Function<T, Boolean> mHasField;
    private final Function<T, Object> mGetField;

    AtomFieldAccessor(
            String fieldName,
            Function<T, Boolean> hasField,
            Function<T, Object> getField) {
        mFieldName = fieldName;
        mHasField = hasField;
        mGetField = getField;
    }

    /**
     * Gets the field name.
     *
     * @return field name as string.
     */
    String getFieldName() {
        return mFieldName;
    }

    /**
     * Checks if the field is set for the provided atom data of type T.
     *
     * @param atomData the atom data in which to check if the field is set.
     * @return whether the field is set.
     */
    Boolean hasField(T atomData) {
        return mHasField.apply(atomData);
    }

    /**
     * Gets the field value for atom data of type T.
     *
     * @param atomData the atom data for which to get the field value from.
     * @return the field value Object.
     */
    Object getField(T atomData) {
        return mGetField.apply(atomData);
    }
}
