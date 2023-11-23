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

package com.android.queryable.queries;

import android.net.Uri;

import com.android.queryable.Queryable;
import com.android.queryable.util.SerializableParcelWrapper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Implementation of {@link UriQuery}. */
public final class UriQueryHelper<E extends Queryable>
        implements UriQuery<E>, Serializable{

    private static final long serialVersionUID = 1;

    private final E mQuery;
    private Uri mEqualsValue;
    private final StringQueryHelper<E> mStringValue = new StringQueryHelper<>();

    UriQueryHelper() {
        mQuery = (E) this;
    }

    public UriQueryHelper(E query) {
        mQuery = query;
    }

    @Override
    public E isEqualTo(Uri uri) {
        this.mEqualsValue = uri;
        return mQuery;
    }

    @Override
    public StringQuery<E> stringValue() {
        return mStringValue;
    }

    @Override
    public boolean matches(Uri value) {
        if (mEqualsValue != null && !mEqualsValue.equals(value)) {
            return false;
        }
        if (!mStringValue.matches(value.toString())) {
            return false;
        }

        return true;
    }

    /**
     * {@code true} if all filters are met by the {@link Uri} contained in
     * {@code serializableUri}.
     */
    public boolean matches(SerializableParcelWrapper<Uri> serializableUri) {
        if ((serializableUri == null || serializableUri.get() == null)) {
            return false;
        }

        return matches(serializableUri.get());
    }

    /**
     * @see #matches(Uri).
     */
    public static boolean matches(UriQueryHelper<?> uriQueryHelper, Uri value) {
        return uriQueryHelper.matches(value);
    }

    @Override
    public String describeQuery(String fieldName) {
        List<String> queryStrings = new ArrayList<>();
        if (mEqualsValue != null) {
            queryStrings.add(fieldName + "=" + mEqualsValue);
        }
        if (mStringValue != null) {
            queryStrings.add(mStringValue.describeQuery(fieldName + ".stringValue"));
        }

        return Queryable.joinQueryStrings(queryStrings);
    }
}
