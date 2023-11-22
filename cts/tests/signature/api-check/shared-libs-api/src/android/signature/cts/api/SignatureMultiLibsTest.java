/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.signature.cts.api;

import android.signature.cts.ApiComplianceChecker;
import android.signature.cts.ApiDocumentParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

/**
 * Performs the signature multi-libs check via a JUnit test.
 */
public class SignatureMultiLibsTest extends SignatureTest {

    private static final String TAG = SignatureMultiLibsTest.class.getSimpleName();

    /**
     * Tests that the device's API matches the expected set defined in xml.
     * <p/>
     * Will check the entire API, and then report the complete list of failures
     */
    public void testSignature() {
        runWithTestResultObserver(mResultObserver -> {

            ApiComplianceChecker complianceChecker =
                    new ApiComplianceChecker(mResultObserver, classProvider);

            ApiDocumentParser apiDocumentParser = new ApiDocumentParser(TAG);

            parseApiFilesAsStream(apiDocumentParser, expectedApiFiles)
                    .forEach(complianceChecker::checkSignatureCompliance);

            // After done parsing all expected API files, perform any deferred checks.
            complianceChecker.checkDeferred();
        });
    }

    private Stream<String> getLibraries() {
        try {
            String result = runShellCommand(getInstrumentation(), "cmd package list libraries");
            return Arrays.stream(result.split("\n")).map(line -> line.split(":")[1]);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkLibrary (String name) {
        String libraryName = name.substring(name.lastIndexOf('/') + 1).split("-")[0];
        return getLibraries().anyMatch(libraryName::equals);
    }

    protected Stream<InputStream> readFile(File file) {
        try {
            if (file.getName().endsWith(".zip")) {
                ZipFile zip = new ZipFile(file);
                return zip.stream().filter(entry -> checkLibrary(entry.getName())).map(entry -> {
                    try {
                        return zip.getInputStream(entry);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }});
            } else {
                return Stream.of(new FileInputStream(file));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
