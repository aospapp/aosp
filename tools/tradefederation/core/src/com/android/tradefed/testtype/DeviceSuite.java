/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.Suite;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

import java.util.HashSet;
import java.util.Set;

/**
 * Extends the JUnit4 container {@link Suite} in order to provide a {@link ITestDevice} to the tests
 * that requires it.
 */
public class DeviceSuite extends Suite
        implements IDeviceTest, IBuildReceiver, IAbiReceiver, ISetOptionReceiver {
    private ITestDevice mDevice;
    private IBuildInfo mBuildInfo;
    private IAbi mAbi;

    @Option(name = HostTest.SET_OPTION_NAME, description = HostTest.SET_OPTION_DESC)
    private Set<String> mKeyValueOptions = new HashSet<>();

    public DeviceSuite(Class<?> klass, RunnerBuilder builder) throws InitializationError {
        super(klass, builder);
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
        for (Runner r : getChildren()) {
            // propagate to runner if it needs a device.
            if (r instanceof IDeviceTest) {
                if (mDevice == null) {
                    throw new IllegalArgumentException("Missing device");
                }
                ((IDeviceTest)r).setDevice(mDevice);
            }
        }
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
        for (Runner r : getChildren()) {
            // propagate to runner if it needs an abi.
            if (r instanceof IAbiReceiver) {
                ((IAbiReceiver)r).setAbi(mAbi);
            }
        }
    }

    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
        for (Runner r : getChildren()) {
            // propagate to runner if it needs a buildInfo.
            if (r instanceof IBuildReceiver) {
                if (mBuildInfo == null) {
                    throw new IllegalArgumentException("Missing build information");
                }
                ((IBuildReceiver)r).setBuild(mBuildInfo);
            }
        }
    }

    @Override
    protected void runChild(Runner runner, RunNotifier notifier) {
        try {
            OptionSetter setter = new OptionSetter(runner);
            for (String kv : mKeyValueOptions) {
                setter.setOptionValue(HostTest.SET_OPTION_NAME, kv);
            }
        } catch (ConfigurationException e) {
            CLog.d("Could not set option set-option on '%s', reason: '%s'", runner, e.getMessage());
        }
        super.runChild(runner, notifier);
    }
}
