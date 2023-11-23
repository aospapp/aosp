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

package com.android.bedstead.nene.benchmarks;

import static com.android.bedstead.nene.permissions.CommonPermissions.INTERACT_ACROSS_USERS;

import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.users.UserBuilder;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.users.UserType;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppProvider;

import java.util.HashMap;
import java.util.Map;

/** Static utilities for fetching current Nene benchmarks. */
public final class BenchmarkDefinition {
    private static final Map<String, Benchmark> benchmarksByName = createBenchmarksMap();

    /** Returns an iterable containing all currently defined Nene benchmarks. */
    public static Iterable<Benchmark> getAllBenchmarks() {
        return benchmarksByName.values();
    }

    /** Fetches a specific benchmark by its name as defined in its {@link BenchmarkMetadata}. */
    public static Benchmark getBenchmark(String name) {
        return benchmarksByName.get(name);
    }

    private static Map<String, Benchmark> createBenchmarksMap() {
        Map<String, Benchmark> benchmarksByName = new HashMap<>();
        for (Benchmark benchmark : getBenchmarksArray()) {
            benchmarksByName.put(benchmark.metadata().getName(), benchmark);
        }
        return benchmarksByName;
    }

    private static Benchmark[] getBenchmarksArray() {
        return new Benchmark[] {
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Create Full User").build()) {
                private final UserBuilder mUserBuilder = TestApis.users().createUser();
                private UserReference mUserReference;

                @Override
                public void run() {
                    mUserReference = mUserBuilder.create();
                }

                @Override
                public void afterIteration() {
                    mUserReference.remove();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Delete Full User").build()) {
                private UserReference mUserReference;

                @Override
                public void beforeIteration() {
                    mUserReference = TestApis.users().createUser().create();
                }

                @Override
                public void run() {
                    mUserReference.remove();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Create Work Profile").build()) {
                private final UserBuilder mUserBuilder =
                        TestApis.users()
                                .createUser()
                                .parent(TestApis.users().instrumented())
                                .type(
                                        TestApis.users()
                                                .supportedType(UserType.MANAGED_PROFILE_TYPE_NAME));
                private UserReference mUserReference;

                @Override
                public void run() {
                    mUserReference = mUserBuilder.create();
                }

                @Override
                public void afterIteration() {
                    mUserReference.remove();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Remove Work Profile").build()) {
                private UserReference mUserReference;

                @Override
                public void beforeIteration() {
                    mUserReference =
                            TestApis.users()
                                    .createUser()
                                    .parent(TestApis.users().instrumented())
                                    .type(
                                            TestApis.users()
                                                    .supportedType(
                                                            UserType.MANAGED_PROFILE_TYPE_NAME))
                                    .create();
                }

                @Override
                public void run() {
                    mUserReference.remove();
                }
            },
            // Note: this benchmark has never succeeded executing without crashing the device
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Switch to User").build()) {
                private UserReference mMainUser;
                private UserReference mOtherUser;

                @Override
                public void beforeBenchmark() {
                    mMainUser = TestApis.users().current();
                }

                @Override
                public void beforeIteration() {
                    // Bug in user APIs - switching away from a user deletes the user, so we have to
                    // recreate it on every iteration
                    mOtherUser = TestApis.users().createUser().createAndStart();
                }

                @Override
                public void run() {
                    mOtherUser.switchTo();
                }

                @Override
                public void afterIteration() {
                    mMainUser.switchTo();
                }

                @Override
                public void afterBenchmark() {
                    mOtherUser.remove();
                }
            },
            new Benchmark(
                    BenchmarkMetadata.newBuilder()
                            .setName("Adopt Permission (INTERACT_ACROSS_USERS)")
                            .build()) {
                private PermissionContext mPermissionContext;

                @Override
                public void run() {
                    mPermissionContext =
                            TestApis.permissions().withPermission(INTERACT_ACROSS_USERS);
                }

                @Override
                public void afterIteration() {
                    mPermissionContext.close();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Query Any Test App").build()) {
                private final TestAppProvider mTestAppProvider = new TestAppProvider();

                @Override
                public void beforeIteration() {
                    mTestAppProvider.snapshot();
                }

                @Override
                public void run() {
                    mTestAppProvider.any();
                }

                @Override
                public void afterIteration() {
                    mTestAppProvider.restore();
                }
            },
            new Benchmark(
                    BenchmarkMetadata.newBuilder()
                            .setName("Query Special Test App (whereActivities().isNotEmpty())")
                            .build()) {
                private final TestAppProvider mTestAppProvider = new TestAppProvider();

                @Override
                public void beforeIteration() {
                    mTestAppProvider.snapshot();
                }

                @Override
                public void run() {
                    mTestAppProvider.query().whereActivities().isNotEmpty().get();
                }

                @Override
                public void afterIteration() {
                    mTestAppProvider.restore();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Install Any Test App").build()) {
                private final TestAppProvider mTestAppProvider = new TestAppProvider();
                private final TestApp mTestApp = mTestAppProvider.any();

                @Override
                public void run() {
                    mTestApp.install();
                }

                @Override
                public void afterIteration() {
                    mTestApp.uninstall();
                }
            },
            new Benchmark(
                    BenchmarkMetadata.newBuilder().setName("Instantiate Device State").build()) {
                @Override
                public void run() {
                    new DeviceState();
                }
            },
            new Benchmark(BenchmarkMetadata.newBuilder().setName("Get System User").build()) {
                @Override
                public void run() {
                    TestApis.users().system();
                }
            },
        };
    }

    private BenchmarkDefinition() {}
}
