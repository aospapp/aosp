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

package android.hdmicec.cts;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.RunUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.rules.ExternalResource;

/** Class that helps communicate with the cec-client */
public final class HdmiCecClientWrapper extends ExternalResource {

    private static final String CEC_CONSOLE_READY = "waiting for input";
    private static final int MILLISECONDS_TO_READY = 10000;
    private static final int DEFAULT_TIMEOUT = 20000;
    private static final String HDMI_CEC_FEATURE = "feature:android.hardware.hdmi.cec";
    private static final int HEXADECIMAL_RADIX = 16;
    private static final int BUFFER_SIZE = 1024;

    private Process mCecClient;
    private BufferedWriter mOutputConsole;
    private BufferedReader mInputConsole;
    private boolean mCecClientInitialised = false;

    private CecDevice targetDevice;
    private BaseHostJUnit4Test testObject;
    private String clientParams[];

    public HdmiCecClientWrapper(CecDevice targetDevice, BaseHostJUnit4Test testObject,
            String ...clientParams) {
        this.targetDevice = targetDevice;
        this.testObject = testObject;
        this.clientParams = clientParams;
    }

    @Override
    protected void before() throws Throwable {
        ITestDevice testDevice;
        testDevice = testObject.getDevice();
        assertNotNull("Device not set", testDevice);

        assumeTrue(isHdmiCecFeatureSupported(testDevice));

        String deviceTypeCsv = testDevice.executeShellCommand("getprop ro.hdmi.device_type").trim();
        List<String> deviceType = Arrays.asList(deviceTypeCsv.replaceAll("\\s+", "").split(","));
        assumeTrue(deviceType.contains(CecDevice.getDeviceType(targetDevice)));

        this.init();
    };

    @Override
    protected void after() {
        this.killCecProcess();
    };

    /**
     * Checks if the HDMI CEC feature is running on the device. Call this function before running
     * any HDMI CEC tests.
     * This could throw a DeviceNotAvailableException.
     */
    private static boolean isHdmiCecFeatureSupported(ITestDevice device) throws Exception {
        return device.hasFeature(HDMI_CEC_FEATURE);
    }

    /** Initialise the client */
    private void init() throws Exception {
        boolean gotExpectedOut = false;
        List<String> commands = new ArrayList();
        int seconds = 0;

        commands.add("cec-client");
        commands.add("-p");
        commands.add("2");
        commands.addAll(Arrays.asList(clientParams));

        mCecClient = RunUtil.getDefault().runCmdInBackground(commands);
        mInputConsole = new BufferedReader(new InputStreamReader(mCecClient.getInputStream()));

        /* Wait for the client to become ready */
        mCecClientInitialised = true;
        if (checkConsoleOutput(CecClientMessage.CLIENT_CONSOLE_READY + "", MILLISECONDS_TO_READY)) {
            mOutputConsole = new BufferedWriter(
                                new OutputStreamWriter(mCecClient.getOutputStream()), BUFFER_SIZE);
            return;
        }

        mCecClientInitialised = false;

        throw (new Exception("Could not initialise cec-client process"));
    }

    private void checkCecClient() throws Exception {
        if (!mCecClientInitialised) {
            throw new Exception("cec-client not initialised!");
        }
        if (!mCecClient.isAlive()) {
            throw new Exception("cec-client not running!");
        }
    }

    /**
     * Sends a CEC message with source marked as broadcast to the device passed in the constructor
     * through the output console of the cec-communication channel.
     */
    public void sendCecMessage(CecMessage message) throws Exception {
        sendCecMessage(CecDevice.BROADCAST, targetDevice, message, "");
    }

    /**
     * Sends a CEC message from source device to the device passed in the constructor through the
     * output console of the cec-communication channel.
     */
    public void sendCecMessage(CecDevice source, CecMessage message) throws Exception {
        sendCecMessage(source, targetDevice, message, "");
    }

    /**
     * Sends a CEC message from source device to a destination device through the output console of
     * the cec-communication channel.
     */
    public void sendCecMessage(CecDevice source, CecDevice destination,
        CecMessage message) throws Exception {
        sendCecMessage(source, destination, message, "");
    }

    /**
     * Sends a CEC message from source device to a destination device through the output console of
     * the cec-communication channel with the appended params.
     */
    public void sendCecMessage(CecDevice source, CecDevice destination,
            CecMessage message, String params) throws Exception {
        checkCecClient();
        mOutputConsole.write("tx " + source + destination + ":" + message + params);
        mOutputConsole.flush();
    }

    /**
     * Sends a <USER_CONTROL_PRESSED> and <USER_CONTROL_RELEASED> from source to destination
     * through the output console of the cec-communication channel with the mentioned keycode.
     */
    public void sendUserControlPressAndRelease(CecDevice source, CecDevice destination,
            int keycode, boolean holdKey) throws Exception {
        sendUserControlPress(source, destination, keycode, holdKey);
        /* Sleep less than 200ms between press and release */
        TimeUnit.MILLISECONDS.sleep(100);
        mOutputConsole.write("tx " + source + destination + ":" +
                              CecMessage.USER_CONTROL_RELEASED);
        mOutputConsole.flush();
    }

    /**
     * Sends a <UCP> message from source to destination through the output console of the
     * cec-communication channel with the mentioned keycode. If holdKey is true, the method will
     * send multiple <UCP> messages to simulate a long press. No <UCR> will be sent.
     */
    public void sendUserControlPress(CecDevice source, CecDevice destination,
            int keycode, boolean holdKey) throws Exception {
        String key = String.format("%02x", keycode);
        String command = "tx " + source + destination + ":" +
                CecMessage.USER_CONTROL_PRESSED + ":" + key;

        if (holdKey) {
            /* Repeat once between 200ms and 450ms for at least 5 seconds. Since message will be
             * sent once later, send 16 times in loop every 300ms. */
            int repeat = 16;
            for (int i = 0; i < repeat; i++) {
                mOutputConsole.write(command);
                mOutputConsole.newLine();
                mOutputConsole.flush();
                TimeUnit.MILLISECONDS.sleep(300);
            }
        }

        mOutputConsole.write(command);
        mOutputConsole.newLine();
        mOutputConsole.flush();
    }

    /**
     * Sends a series of <UCP> [firstKeycode] from source to destination through the output console
     * of the cec-communication channel immediately followed by <UCP> [secondKeycode]. No <UCR>
     *  message is sent.
     */
    public void sendUserControlInterruptedPressAndHold(CecDevice source, CecDevice destination,
            int firstKeycode, int secondKeycode, boolean holdKey) throws Exception {
        sendUserControlPress(source, destination, firstKeycode, holdKey);
        /* Sleep less than 200ms between press and release */
        TimeUnit.MILLISECONDS.sleep(100);
        sendUserControlPress(source, destination, secondKeycode, false);
    }

    /** Sends a message to the output console of the cec-client */
    public void sendConsoleMessage(String message) throws Exception {
        checkCecClient();
        CLog.v("Sending message:: " + message);
        mOutputConsole.write(message);
        mOutputConsole.flush();
    }

    /** Check for any string on the input console of the cec-client, uses default timeout */
    public boolean checkConsoleOutput(String expectedMessage) throws Exception {
        return checkConsoleOutput(expectedMessage, DEFAULT_TIMEOUT);
    }

    /** Check for any string on the input console of the cec-client */
    public boolean checkConsoleOutput(String expectedMessage,
                                       long timeoutMillis) throws Exception {
        checkCecClient();
        long startTime = System.currentTimeMillis();
        long endTime = startTime;

        while ((endTime - startTime <= timeoutMillis)) {
            if (mInputConsole.ready()) {
                String line = mInputConsole.readLine();
                if (line.contains(expectedMessage)) {
                    CLog.v("Found " + expectedMessage + " in " + line);
                    return true;
                }
            }
            endTime = System.currentTimeMillis();
        }
        return false;
    }

    /**
     * Looks for the CEC expectedMessage broadcast on the cec-client communication channel and
     * returns the first line that contains that message within default timeout. If the CEC message
     * is not found within the timeout, an exception is thrown.
     */
    public String checkExpectedOutput(CecMessage expectedMessage) throws Exception {
        return checkExpectedOutput(CecDevice.BROADCAST, expectedMessage, DEFAULT_TIMEOUT);
    }

    /**
     * Looks for the CEC expectedMessage sent to CEC device toDevice on the cec-client
     * communication channel and returns the first line that contains that message within
     * default timeout. If the CEC message is not found within the timeout, an exception is thrown.
     */
    public String checkExpectedOutput(CecDevice toDevice,
                                      CecMessage expectedMessage) throws Exception {
        return checkExpectedOutput(toDevice, expectedMessage, DEFAULT_TIMEOUT);
    }

    /**
     * Looks for the CEC expectedMessage broadcast on the cec-client communication channel and
     * returns the first line that contains that message within timeoutMillis. If the CEC message
     * is not found within the timeout, an exception is thrown.
     */
    public String checkExpectedOutput(CecMessage expectedMessage,
                                      long timeoutMillis) throws Exception {
        return checkExpectedOutput(CecDevice.BROADCAST, expectedMessage, timeoutMillis);
    }

    /**
     * Looks for the CEC expectedMessage sent to CEC device toDevice on the cec-client
     * communication channel and returns the first line that contains that message within
     * timeoutMillis. If the CEC message is not found within the timeout, an exception is thrown.
     */
    public String checkExpectedOutput(CecDevice toDevice, CecMessage expectedMessage,
                                       long timeoutMillis) throws Exception {
        checkCecClient();
        long startTime = System.currentTimeMillis();
        long endTime = startTime;
        Pattern pattern = Pattern.compile("(.*>>)(.*?)" +
                                          "(" + targetDevice + toDevice + "):" +
                                          "(" + expectedMessage + ")(.*)",
                                          Pattern.CASE_INSENSITIVE);

        while ((endTime - startTime <= timeoutMillis)) {
            if (mInputConsole.ready()) {
                String line = mInputConsole.readLine();
                if (pattern.matcher(line).matches()) {
                    CLog.v("Found " + expectedMessage.name() + " in " + line);
                    return line;
                }
            }
            endTime = System.currentTimeMillis();
        }
        throw new Exception("Could not find message " + expectedMessage.name());
    }

    /**
     * Looks for the CEC message incorrectMessage sent to CEC device toDevice on the cec-client
     * communication channel and throws an exception if it finds the line that contains the message
     * within the default timeout. If the CEC message is not found within the timeout, function
     * returns without error.
     */
    public void checkOutputDoesNotContainMessage(CecDevice toDevice,
            CecMessage incorrectMessage) throws Exception {
        checkOutputDoesNotContainMessage(toDevice, incorrectMessage, DEFAULT_TIMEOUT);
     }

    /**
     * Looks for the CEC message incorrectMessage sent to CEC device toDevice on the cec-client
     * communication channel and throws an exception if it finds the line that contains the message
     * within timeoutMillis. If the CEC message is not found within the timeout, function returns
     * without error.
     */
    public void checkOutputDoesNotContainMessage(CecDevice toDevice, CecMessage incorrectMessage,
            long timeoutMillis) throws Exception {

        checkCecClient();
        long startTime = System.currentTimeMillis();
        long endTime = startTime;
        Pattern pattern = Pattern.compile("(.*>>)(.*?)" +
                                          "(" + targetDevice + toDevice + "):" +
                                          "(" + incorrectMessage + ")(.*)",
                                          Pattern.CASE_INSENSITIVE);

        while ((endTime - startTime <= timeoutMillis)) {
            if (mInputConsole.ready()) {
                String line = mInputConsole.readLine();
                if (pattern.matcher(line).matches()) {
                    CLog.v("Found " + incorrectMessage.name() + " in " + line);
                    throw new Exception("Found " + incorrectMessage.name() + " to " + toDevice +
                            " with params " + getParamsFromMessage(line));
                }
            }
            endTime = System.currentTimeMillis();
        }
     }

    /** Gets the hexadecimal ASCII character values of a string. */
    public String getHexAsciiString(String string) {
        String asciiString = "";
        byte[] ascii = string.trim().getBytes();

        for (byte b : ascii) {
            asciiString.concat(Integer.toHexString(b));
        }

        return asciiString;
    }

    public String formatParams(String rawParams) {
        StringBuilder params = new StringBuilder("");
        int position = 0;
        int endPosition = 2;

        do {
            params.append(":" + rawParams.substring(position, endPosition));
            position = endPosition;
            endPosition += 2;
        } while (endPosition <= rawParams.length());
        return params.toString();
    }

    public String formatParams(long rawParam) {
        StringBuilder params = new StringBuilder("");

        do {
            params.insert(0, ":" + String.format("%02x", rawParam % 256));
            rawParam >>= 8;
        } while (rawParam > 0);

        return params.toString();
    }

    /** Formats a CEC message in the hex colon format (sd:op:xx:xx). */
    public String formatMessage(CecDevice source, CecDevice destination, CecMessage message,
            int params) {
        StringBuilder cecMessage = new StringBuilder("" + source + destination + ":" + message);

        cecMessage.append(formatParams(params));

        return cecMessage.toString();
    }

    public static int hexStringToInt(String message) {
        return Integer.parseInt(message, HEXADECIMAL_RADIX);
    }

    public String getAsciiStringFromMessage(String message) {
        String params = getNibbles(message).substring(4);
        StringBuilder builder = new StringBuilder();

        for (int i = 2; i <= params.length(); i += 2) {
            builder.append((char) hexStringToInt(params.substring(i - 2, i)));
        }

        return builder.toString();
    }

    /**
     * Gets the params from a CEC message.
     */
    public int getParamsFromMessage(String message) {
        return hexStringToInt(getNibbles(message).substring(4));
    }

    /**
     * Gets the first 'numNibbles' number of param nibbles from a CEC message.
     */
    public int getParamsFromMessage(String message, int numNibbles) {
        int paramStart = 4;
        int end = numNibbles + paramStart;
        return hexStringToInt(getNibbles(message).substring(paramStart, end));
    }

    /**
     * From the params of a CEC message, gets the nibbles from position start to position end.
     * The start and end are relative to the beginning of the params. For example, in the following
     * message - 4F:82:10:00:04, getParamsFromMessage(message, 0, 4) will return 0x1000 and
     * getParamsFromMessage(message, 4, 6) will return 0x04.
     */
    public int getParamsFromMessage(String message, int start, int end) {
        return hexStringToInt(getNibbles(message).substring(4).substring(start, end));
    }

    /**
     * Gets the source logical address from a CEC message.
     */
    public CecDevice getSourceFromMessage(String message) {
        String param = getNibbles(message).substring(0, 1);
        return CecDevice.getDevice(hexStringToInt(param));
    }

    /**
     * Converts ascii characters to hexadecimal numbers that can be appended to a CEC message as
     * params. For example, "spa" will be converted to ":73:70:61"
     */
    public static String convertStringToHexParams(String rawParams) {
        StringBuilder params = new StringBuilder("");
        for (int i = 0; i < rawParams.length(); i++) {
            params.append(String.format(":%02x", (int) rawParams.charAt(i)));
        }
        return params.toString();
    }


    /**
     * Gets the destination logical address from a CEC message.
     */
    public CecDevice getDestinationFromMessage(String message) {
        String param = getNibbles(message).substring(1, 2);
        return CecDevice.getDevice(hexStringToInt(param));
    }

    private String getNibbles(String message) {
        final String tag1 = "group1";
        final String tag2 = "group2";
        String paramsPattern = "(?:.*[>>|<<].*?)" +
                               "(?<" + tag1 + ">[\\p{XDigit}{2}:]+)" +
                               "(?<" + tag2 + ">\\p{XDigit}{2})" +
                               "(?:.*?)";
        String nibbles = "";

        Pattern p = Pattern.compile(paramsPattern);
        Matcher m = p.matcher(message);
        if (m.matches()) {
            nibbles = m.group(tag1).replace(":", "") + m.group(tag2);
        }
        return nibbles;
    }

    /**
     * Kills the cec-client process that was created in init().
     */
    private void killCecProcess() {
        try {
            checkCecClient();
            sendConsoleMessage(CecClientMessage.QUIT_CLIENT.toString());
            mOutputConsole.close();
            mInputConsole.close();
            mCecClientInitialised = false;
            if (!mCecClient.waitFor(MILLISECONDS_TO_READY, TimeUnit.MILLISECONDS)) {
                /* Use a pkill cec-client if the cec-client process is not dead in spite of the
                 * quit above.
                 */
                List<String> commands = new ArrayList<>();
                Process killProcess;
                commands.add("pkill");
                commands.add("cec-client");
                killProcess = RunUtil.getDefault().runCmdInBackground(commands);
                killProcess.waitFor();
            }
        } catch (Exception e) {
            /* If cec-client is not running, do not throw an exception, just return. */
            CLog.w("Unable to close cec-client", e);
        }
    }
}
