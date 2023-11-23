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

import android.content.IntentFilter;

import com.android.queryable.Queryable;
import com.android.queryable.info.ServiceInfo;

/**
 * Implementation of {@link ServiceQuery}.
 *
 * @param <E> Type of query.
 */
public final class ServiceQueryHelper<E extends Queryable> implements ServiceQuery<E> {

    private final E mQuery;
    private final ClassQueryHelper<E> mServiceClassQueryHelper;
    private final SetQueryHelper<E, IntentFilter, IntentFilterQuery<?>>
            mIntentFiltersQueryHelper;

    ServiceQueryHelper() {
        mQuery = (E) this;
        mServiceClassQueryHelper = new ClassQueryHelper<>(mQuery);
        mIntentFiltersQueryHelper = new SetQueryHelper<>(mQuery);
    }

    public ServiceQueryHelper(E query) {
        mQuery = query;
        mServiceClassQueryHelper = new ClassQueryHelper<>(query);
        mIntentFiltersQueryHelper = new SetQueryHelper<>(query);
    }

    @Override
    public ClassQuery<E> serviceClass() {
        return mServiceClassQueryHelper;
    }

    @Override
    public SetQuery<E, IntentFilter, IntentFilterQuery<?>> intentFilters() {
        return mIntentFiltersQueryHelper;
    }

    @Override
    public boolean matches(ServiceInfo value) {
        return mServiceClassQueryHelper.matches(value)
                && mIntentFiltersQueryHelper.matches(value.intentFilters());
    }

    @Override
    public String describeQuery(String fieldName) {
        return Queryable.joinQueryStrings(
                mServiceClassQueryHelper.describeQuery(fieldName + ".service"),
                mIntentFiltersQueryHelper.describeQuery(fieldName + ".intentFilters")
        );
    }

    /**
     * Check if a {@link ServiceQueryHelper} matches the service represented by a
     * {@link ServiceInfo}.
     */
    public static boolean matches(ServiceQueryHelper<?> serviceQueryHelper, ServiceInfo value) {
        return serviceQueryHelper.matches(value);
    }
}
