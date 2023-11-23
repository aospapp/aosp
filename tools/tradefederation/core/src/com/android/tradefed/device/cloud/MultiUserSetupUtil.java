/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.device.cloud;

import com.android.tradefed.device.TestDeviceOptions;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;

import java.util.Arrays;
import java.util.List;

/** Utility to create another user in Cuttlefish VM. New user will allow to run a second device. */
public class MultiUserSetupUtil {

    /** Files that must be copied between users to avoid conflicting ownership */
    private static final List<String> FILE_TO_BE_COPIED =
            Arrays.asList("android-info.txt", "*.img");

    /** Files that can simply be shared between the different users */
    private static final List<String> FILE_TO_BE_LINKED = Arrays.asList("bin", "config", "lib64");

    /** Setup a new remote user on an existing Cuttlefish VM. */
    public static CommandResult prepareRemoteUser(
            String username,
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs) {
        // First create the user
        String createUserCommand =
                "sudo useradd " + username + " -G sudo,kvm,cvdnetwork -m -s /bin/bash -p '*'";
        CommandResult createUserRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance, options, runUtil, timeoutMs, createUserCommand.split(" "));
        if (!CommandStatus.SUCCESS.equals(createUserRes.getStatus())) {
            return createUserRes;
        }
        return null;
    }

    /** Create the 'cvd-XX' user on the remote device if missing. */
    public static CommandResult addExtraCvdUser(
            int userId,
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs) {
        String useridString = getUserNumber(userId);
        String username = String.format("cvd-%s", useridString);
        String createUserCommand =
                "sudo useradd " + username + " -G plugdev -m -s /bin/bash -p '*'";
        CommandResult createUserRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance, options, runUtil, timeoutMs, createUserCommand.split(" "));
        if (!CommandStatus.SUCCESS.equals(createUserRes.getStatus())) {
            if (createUserRes
                    .getStderr()
                    .contains(String.format("user '%s' already exists", username))) {
                return null;
            }
            return createUserRes;
        }
        return null;
    }

    /** Setup the tuntap interface required to start the Android devices if they are missing. */
    public static CommandResult setupNetworkInterface(
            int userId,
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs) {
        if (userId < 9) {
            // TODO: use 'tuntap show' to check if interface exists already or not.
            return null;
        }
        String useridString = getUserNumber(userId);
        String mtap = String.format("cvd-mtap-%s", useridString);
        String wtap = String.format("cvd-wtap-%s", useridString);
        String addNetworkInterface = "sudo ip tuntap add dev %s mode tap group cvdnetwork";
        String mtapCommand = String.format(addNetworkInterface, mtap);
        CommandResult addNetworkInterfaceRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance, options, runUtil, timeoutMs, mtapCommand.split(" "));
        if (!CommandStatus.SUCCESS.equals(addNetworkInterfaceRes.getStatus())) {
            return addNetworkInterfaceRes;
        }

        String wtapCommand = String.format(addNetworkInterface, wtap);
        addNetworkInterfaceRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance, options, runUtil, timeoutMs, wtapCommand.split(" "));
        if (!CommandStatus.SUCCESS.equals(addNetworkInterfaceRes.getStatus())) {
            return addNetworkInterfaceRes;
        }
        return null;
    }

    /** Setup a new remote user on an existing Cuttlefish VM. */
    public static CommandResult prepareRemoteHomeDir(
            String mainRootUser,
            String username,
            GceAvdInfo remoteInstance,
            TestDeviceOptions options,
            IRunUtil runUtil,
            long timeoutMs) {
        StringBuilder copyCommandBuilder = new StringBuilder("sudo cp ");
        for (String file : FILE_TO_BE_COPIED) {
            copyCommandBuilder.append(" /home/" + mainRootUser + "/" + file);
        }
        copyCommandBuilder.append(" /home/" + username + "/");
        CommandResult cpRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance,
                        options,
                        runUtil,
                        timeoutMs,
                        copyCommandBuilder.toString().split(" "));
        if (!CommandStatus.SUCCESS.equals(cpRes.getStatus())) {
            return cpRes;
        }
        // Own the copied files
        String chownUser = getChownCommand(username);
        CommandResult chownRes =
                RemoteSshUtil.remoteSshCommandExec(
                        remoteInstance, options, runUtil, timeoutMs, chownUser);
        if (!CommandStatus.SUCCESS.equals(chownRes.getStatus())) {
            return chownRes;
        }
        // Link files that can be shared between users
        for (String file : FILE_TO_BE_LINKED) {
            String copyDevice =
                    "sudo ln -s /home/" + mainRootUser + "/" + file + " /home/" + username + "/";
            CommandResult copyRes =
                    RemoteSshUtil.remoteSshCommandExec(
                            remoteInstance, options, runUtil, timeoutMs, copyDevice.split(" "));
            if (!CommandStatus.SUCCESS.equals(copyRes.getStatus())) {
                return copyRes;
            }
        }
        return null;
    }

    /** Gets the command for a user to own the main directory. */
    public static String getChownCommand(String username) {
        return "find /home/" + username + " | sudo xargs chown " + username;
    }

    /** Returns the user id string version that follow the remote device notation. */
    public static String getUserNumber(int userId) {
        return (userId > 9) ? Integer.toString(userId) : "0" + Integer.toString(userId);
    }
}
