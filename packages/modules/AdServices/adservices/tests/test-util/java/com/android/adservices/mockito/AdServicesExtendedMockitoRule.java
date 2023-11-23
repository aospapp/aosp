/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.adservices.mockito;

import androidx.annotation.Nullable;

import com.android.modules.utils.testing.StaticMockFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Abstraction for {@link ExtendedMockitoRule}. */
public class AdServicesExtendedMockitoRule extends ExtendedMockitoRule {

    @SafeVarargs
    public AdServicesExtendedMockitoRule(Supplier<? extends StaticMockFixture>... suppliers) {
        super(new ExtendedMockitoRule.Builder().addStaticMockFixtures(suppliers));
    }

    private AdServicesExtendedMockitoRule(Builder builder) {
        super(builder.asExtendedMockitoRuleBuilder());
    }

    // TODO(b/281577492): make ExtendedMockitoRule.Builder() non-final and extend it instead?
    public static final class Builder {
        private final @Nullable Object mTestClassInstance;
        private final List<Class<?>> mMockedStaticClasses = new ArrayList<>();
        private final List<Class<?>> mSpiedStaticClasses = new ArrayList<>();
        private final List<Supplier<? extends StaticMockFixture>> mSuppliers = new ArrayList<>();

        public Builder() {
            this(/* testClassInstance= */ null);
        }

        public Builder(Object testClassInstance) {
            mTestClassInstance = testClassInstance;
        }

        public Builder mockStatic(Class<?> clazz) {
            mMockedStaticClasses.add(clazz);
            return this;
        }

        public Builder spyStatic(Class<?> clazz) {
            mSpiedStaticClasses.add(clazz);
            return this;
        }

        @SafeVarargs
        public final Builder addStaticMockFixtures(
                Supplier<? extends StaticMockFixture>... suppliers) {
            for (Supplier<? extends StaticMockFixture> supplier : suppliers) {
                mSuppliers.add(supplier);
            }
            return this;
        }

        public AdServicesExtendedMockitoRule build() {
            return new AdServicesExtendedMockitoRule(this);
        }

        private ExtendedMockitoRule.Builder asExtendedMockitoRuleBuilder() {
            ExtendedMockitoRule.Builder builder =
                    mTestClassInstance == null
                            ? new ExtendedMockitoRule.Builder()
                            : new ExtendedMockitoRule.Builder(mTestClassInstance);
            mMockedStaticClasses.forEach(c -> builder.mockStatic(c));
            mSpiedStaticClasses.forEach(c -> builder.spyStatic(c));
            mSuppliers.forEach(s -> builder.addStaticMockFixtures(s));
            return builder;
        }
    }
}
