/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.Bundle;
import android.signature.cts.ApiDocumentParser;
import android.signature.cts.ClassProvider;
import android.signature.cts.ExcludingClassProvider;
import android.signature.cts.FailureType;
import android.signature.cts.JDiffClassDescription;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.xmlpull.v1.XmlPullParserException;
import repackaged.android.test.InstrumentationTestCase;
import repackaged.android.test.InstrumentationTestRunner;

import static android.signature.cts.CurrentApi.API_FILE_DIRECTORY;

/**
 */
public class AbstractApiTest extends InstrumentationTestCase {

    private TestResultObserver mResultObserver;

    ClassProvider classProvider;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResultObserver = new TestResultObserver();

        // Get the arguments passed to the instrumentation.
        Bundle instrumentationArgs =
                ((InstrumentationTestRunner) getInstrumentation()).getArguments();

        // Prepare for a class provider that loads classes from bootclasspath but filters
        // out known inaccessible classes.
        // Note that com.android.internal.R.* inner classes are also excluded as they are
        // not part of API though exist in the runtime.
        classProvider = new ExcludingClassProvider(
                new BootClassPathClassesProvider(),
                name -> name != null && name.startsWith("com.android.internal.R."));

        initializeFromArgs(instrumentationArgs);
    }

    protected void initializeFromArgs(Bundle instrumentationArgs) throws Exception {

    }

    protected interface RunnableWithTestResultObserver {
        void run(TestResultObserver observer) throws Exception;
    }

    void runWithTestResultObserver(RunnableWithTestResultObserver runnable) {
        try {
            runnable.run(mResultObserver);
        } catch (Exception e) {
            StringWriter writer = new StringWriter();
            writer.write(e.toString());
            writer.write("\n");
            e.printStackTrace(new PrintWriter(writer));
            mResultObserver.notifyFailure(FailureType.CAUGHT_EXCEPTION, e.getClass().getName(),
                    writer.toString());
        }
        if (mResultObserver.mDidFail) {
            StringBuilder errorString = mResultObserver.mErrorString;
            ClassLoader classLoader = getClass().getClassLoader();
            errorString.append("\nClassLoader hierarchy\n");
            while (classLoader != null) {
                errorString.append("    ").append(classLoader).append("\n");
                classLoader = classLoader.getParent();
            }
            fail(errorString.toString());
        }
    }

    static String[] getCommaSeparatedList(Bundle instrumentationArgs, String key) {
        String argument = instrumentationArgs.getString(key);
        if (argument == null) {
            return new String[0];
        }
        return argument.split(",");
    }

    Stream<Object> readFileOptimized(File file) {
        try {
            if (file.getName().endsWith(".zip")) {
                @SuppressWarnings("resource")
                ZipFile zip = new ZipFile(file);
                return zip.stream().map(entry -> {
                    try {
                        return zip.getInputStream(entry);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } else {
                try (FileChannel fileChannel = (FileChannel) Files.newByteChannel(file.toPath(),
                        EnumSet.of(StandardOpenOption.READ))) {
                    ByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0,
                            fileChannel.size());
                    if (mappedByteBuffer == null) {
                        throw new IllegalStateException("Could not map " + file);
                    }
                    return Stream.of(mappedByteBuffer);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    Stream<InputStream> readFile(File file) {
        try {
            if (file.getName().endsWith(".zip")) {
                @SuppressWarnings("resource")
                ZipFile zip = new ZipFile(file);
                return zip.stream().map(entry -> {
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

    Stream<JDiffClassDescription> parseApiFilesAsStream(
            ApiDocumentParser apiDocumentParser, String[] apiFiles) {
        return Stream.of(apiFiles)
                .map(name -> new File(API_FILE_DIRECTORY + "/" + name))
                .flatMap(this::readFile)
                .flatMap(stream -> {
                    try {
                        return apiDocumentParser.parseAsStream(stream);
                    } catch (IOException | XmlPullParserException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
