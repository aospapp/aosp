/*
 * Copyright (C) 2018 Google Inc.
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
package com.android.tradefed.targetprep;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Target preparer that triggers fastboot and sends fastboot commands.
 *
 * <p>TODO(b/122592575): Add tests for this preparer.
 */
@OptionClass(alias = "fastboot-command-preparer")
public class FastbootCommandPreparer extends BaseTargetPreparer {
    @Option(name = "command", description = "Fastboot commands to run.")
    private List<String> mFastbootCommands = new ArrayList<String>();

    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {
        device.rebootIntoBootloader();

        for (String cmd : mFastbootCommands) {
            // Ignore reboots since we'll reboot in the end.
            if (cmd.trim().equals("reboot")) {
                continue;
            }

            device.executeFastbootCommand(cmd.split("\\s+"));
        }

        device.reboot();
    }
}
