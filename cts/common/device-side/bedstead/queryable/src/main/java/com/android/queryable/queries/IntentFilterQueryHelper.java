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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Implementation of {@link IntentFilterQuery}.
 *
 * @param <E> Type of query.
 */
public class IntentFilterQueryHelper<E extends Queryable> implements IntentFilterQuery<E> {

    private final E mQuery;
    private final SetQueryHelper<E, String, StringQuery<?>> mActionsQueryHelper;
    private final SetQueryHelper<E, String, StringQuery<?>> mCategoriesQueryHelper;

    IntentFilterQueryHelper() {
        mQuery = (E) this;
        mActionsQueryHelper = new SetQueryHelper<>(mQuery);
        mCategoriesQueryHelper = new SetQueryHelper<>(mQuery);
    }

    public IntentFilterQueryHelper(E query) {
        mQuery = query;
        mActionsQueryHelper = new SetQueryHelper<>(query);
        mCategoriesQueryHelper = new SetQueryHelper<>(query);
    }

    @Override
    public SetQuery<E, String, StringQuery<?>> actions() {
        return mActionsQueryHelper;
    }

    @Override
    public SetQuery<E, String, StringQuery<?>> categories() {
        return mCategoriesQueryHelper;
    }

    @Override
    public boolean matches(IntentFilter value) {
        Set<String> actions = new HashSet<>();
        Set<String> categories = new HashSet<>();

        if (value.countActions() > 0) {
            Iterator<String> actionsIterator = value.actionsIterator();

            while (actionsIterator.hasNext()) {
                actions.add(actionsIterator.next());
            }
        }
        if (value.countCategories() > 0) {
            Iterator<String> categoriesIterator = value.categoriesIterator();

            while (categoriesIterator.hasNext()) {
                categories.add(categoriesIterator.next());
            }
        }


        return mActionsQueryHelper.matches(actions)
                && mCategoriesQueryHelper.matches(categories);
    }

    @Override
    public String describeQuery(String fieldName) {
        return Queryable.joinQueryStrings(
                mActionsQueryHelper.describeQuery(fieldName + ".actions"),
                mCategoriesQueryHelper.describeQuery(fieldName + ".categories")
        );
    }
}
