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

package com.android.tools.metalava.apilevels

import com.android.tools.metalava.ARG_ANDROID_JAR_PATTERN
import com.android.tools.metalava.ARG_CURRENT_CODENAME
import com.android.tools.metalava.ARG_CURRENT_VERSION
import com.android.tools.metalava.ARG_FIRST_VERSION
import com.android.tools.metalava.ARG_GENERATE_API_LEVELS
import com.android.tools.metalava.ARG_REMOVE_MISSING_CLASS_REFERENCES_IN_API_LEVELS
import com.android.tools.metalava.ARG_SDK_INFO_FILE
import com.android.tools.metalava.ARG_SDK_JAR_ROOT
import com.android.tools.metalava.DriverTest
import com.android.tools.metalava.getApiLookup
import com.android.tools.metalava.java
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.text.Charsets.UTF_8

class ApiGeneratorTest : DriverTest() {
    @Test
    fun `Extract API levels`() {
        var oldSdkJars = File("prebuilts/tools/common/api-versions")
        if (!oldSdkJars.isDirectory) {
            oldSdkJars = File("../../prebuilts/tools/common/api-versions")
            if (!oldSdkJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found - is \$PWD set to an Android source tree?")
                return
            }
        }

        var platformJars = File("prebuilts/sdk")
        if (!platformJars.isDirectory) {
            platformJars = File("../../prebuilts/sdk")
            if (!platformJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found: $platformJars")
                return
            }
        }
        val output = File.createTempFile("api-info", "xml")
        output.deleteOnExit()
        val outputPath = output.path

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                outputPath,
                ARG_ANDROID_JAR_PATTERN,
                "${oldSdkJars.path}/android-%/android.jar",
                ARG_ANDROID_JAR_PATTERN,
                "${platformJars.path}/%/public/android.jar",
                ARG_CURRENT_CODENAME,
                "Z",
                ARG_CURRENT_VERSION,
                "89" // not real api level of Z
            ),
            sourceFiles = arrayOf(
                java(
                    """
                    package android.pkg;
                    public class MyTest {
                    }
                    """
                )
            )
        )

        assertTrue(output.isFile)

        val xml = output.readText(UTF_8)
        assertTrue(xml.contains("<class name=\"android/Manifest\$permission\" since=\"1\">"))
        assertTrue(xml.contains("<field name=\"BIND_CARRIER_MESSAGING_SERVICE\" since=\"22\" deprecated=\"23\"/>"))
        assertTrue(xml.contains("<class name=\"android/pkg/MyTest\" since=\"90\""))
        assertFalse(xml.contains("<implements name=\"java/lang/annotation/Annotation\" removed=\""))
        assertFalse(xml.contains("<extends name=\"java/lang/Enum\" removed=\""))
        assertFalse(xml.contains("<method name=\"append(C)Ljava/lang/AbstractStringBuilder;\""))

        // Also make sure package private super classes are pruned
        assertFalse(xml.contains("<extends name=\"android/icu/util/CECalendar\""))

        val apiLookup = getApiLookup(output, temporaryFolder.newFolder())

        // Make sure we're really using the correct database, not the SDK one. (This placeholder
        // class is provided as a source file above.)
        assertEquals(90, apiLookup.getClassVersion("android.pkg.MyTest"))

        apiLookup.getClassVersion("android.v")
        assertEquals(5, apiLookup.getFieldVersion("android.Manifest\$permission", "AUTHENTICATE_ACCOUNTS"))

        val methodVersion = apiLookup.getMethodVersion("android/icu/util/CopticCalendar", "computeTime", "()")
        assertEquals(24, methodVersion)
    }

    @Test
    fun `Extract System API`() {
        // These are the wrong jar paths but this test doesn't actually care what the
        // content of the jar files, just checking the logic of starting the database
        // at some higher number than 1
        var platformJars = File("prebuilts/sdk")
        if (!platformJars.isDirectory) {
            platformJars = File("../../prebuilts/sdk")
            if (!platformJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found: $platformJars")
                return
            }
        }

        var extensionSdkJars = File("prebuilts/sdk/extensions")
        if (!extensionSdkJars.isDirectory) {
            extensionSdkJars = File("../../prebuilts/sdk/extensions")
            if (!extensionSdkJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found: $extensionSdkJars")
                return
            }
        }

        val filter = File.createTempFile("filter", "txt")
        filter.deleteOnExit()
        filter.writeText(
            """
                <sdk-extensions-info>
                <!-- SDK definitions -->
                <sdk shortname="R" name="R Extensions" id="30" reference="android/os/Build${'$'}VERSION_CODES${'$'}R" />
                <sdk shortname="S" name="S Extensions" id="31" reference="android/os/Build${'$'}VERSION_CODES${'$'}S" />
                <sdk shortname="T" name="T Extensions" id="33" reference="android/os/Build${'$'}VERSION_CODES${'$'}T" />

                <!-- Rules -->
                <symbol jar="art.module.public.api" pattern="*" sdks="R" />
                <symbol jar="conscrypt.module.intra.core.api " pattern="" sdks="R" />
                <symbol jar="conscrypt.module.platform.api" pattern="*" sdks="R" />
                <symbol jar="conscrypt.module.public.api" pattern="*" sdks="R" />
                <symbol jar="framework-mediaprovider" pattern="*" sdks="R" />
                <symbol jar="framework-mediaprovider" pattern="android.provider.MediaStore#canManageMedia" sdks="T" />
                <symbol jar="framework-permission-s" pattern="*" sdks="R" />
                <symbol jar="framework-permission" pattern="*" sdks="R" />
                <symbol jar="framework-sdkextensions" pattern="*" sdks="R" />
                <symbol jar="framework-scheduling" pattern="*" sdks="R" />
                <symbol jar="framework-statsd" pattern="*" sdks="R" />
                <symbol jar="framework-tethering" pattern="*" sdks="R" />
                <symbol jar="legacy.art.module.platform.api" pattern="*" sdks="R" />
                <symbol jar="service-media-s" pattern="*" sdks="R" />
                <symbol jar="service-permission" pattern="*" sdks="R" />

                <!-- use framework-permissions-s to test the order of multiple SDKs is respected -->
                <symbol jar="android.net.ipsec.ike" pattern="android.net.eap.EapAkaInfo" sdks="R,S,T" />
                <symbol jar="android.net.ipsec.ike" pattern="android.net.eap.EapInfo" sdks="T,S,R" />
                <symbol jar="android.net.ipsec.ike" pattern="*" sdks="R" />

                <!-- framework-connectivity: only android.net.CaptivePortal should have the 'sdks' attribute -->
                <symbol jar="framework-connectivity" pattern="android.net.CaptivePortal" sdks="R" />

                <!-- framework-media explicitly omitted: nothing in this module should have the 'sdks' attribute -->
                </sdk-extensions-info>
            """.trimIndent()
        )

        val output = File.createTempFile("api-info", "xml")
        output.deleteOnExit()
        val outputPath = output.path

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                outputPath,
                ARG_ANDROID_JAR_PATTERN,
                "${platformJars.path}/%/public/android.jar",
                ARG_SDK_JAR_ROOT,
                "$extensionSdkJars",
                ARG_SDK_INFO_FILE,
                filter.path,
                ARG_FIRST_VERSION,
                "21",
                ARG_CURRENT_VERSION,
                "33"
            )
        )

        assertTrue(output.isFile)
        val xml = output.readText(UTF_8)
        assertTrue(xml.contains("<api version=\"3\" min=\"21\">"))
        assertTrue(xml.contains("<sdk id=\"30\" shortname=\"R\" name=\"R Extensions\" reference=\"android/os/Build\$VERSION_CODES\$R\"/>"))
        assertTrue(xml.contains("<sdk id=\"31\" shortname=\"S\" name=\"S Extensions\" reference=\"android/os/Build\$VERSION_CODES\$S\"/>"))
        assertTrue(xml.contains("<sdk id=\"33\" shortname=\"T\" name=\"T Extensions\" reference=\"android/os/Build\$VERSION_CODES\$T\"/>"))
        assertTrue(xml.contains("<class name=\"android/Manifest\" since=\"21\">"))
        assertTrue(xml.contains("<field name=\"showWhenLocked\" since=\"27\"/>"))

        // top level class marked as since=21 and R=1, implemented in the framework-mediaprovider mainline module
        assertTrue(xml.contains("<class name=\"android/provider/MediaStore\" module=\"framework-mediaprovider\" since=\"21\" sdks=\"30:1,0:21\">"))

        // method with identical sdks attribute as containing class: sdks attribute should be omitted
        assertTrue(xml.contains("<method name=\"getMediaScannerUri()Landroid/net/Uri;\"/>"))

        // method with different sdks attribute than containing class
        assertTrue(xml.contains("<method name=\"canManageMedia(Landroid/content/Context;)Z\" since=\"31\" sdks=\"33:1,0:31\"/>"))

        val apiLookup = getApiLookup(output)
        apiLookup.getClassVersion("android.v")
        // This field was added in API level 5, but when we're starting the count higher
        // (as in the system API), the first introduced API level is the one we use
        assertEquals(21, apiLookup.getFieldVersion("android.Manifest\$permission", "AUTHENTICATE_ACCOUNTS"))

        val methodVersion = apiLookup.getMethodVersion("android/icu/util/CopticCalendar", "computeTime", "()")
        assertEquals(24, methodVersion)

        // The filter says 'framework-permission-s             *    R' so RoleManager should exist and should have a module/sdks attributes
        assertTrue(apiLookup.containsClass("android/app/role/RoleManager"))
        assertTrue(xml.contains("<method name=\"canManageMedia(Landroid/content/Context;)Z\" since=\"31\" sdks=\"33:1,0:31\"/>"))

        // The filter doesn't mention framework-media, so no class in that module should have a module/sdks attributes
        assertTrue(xml.contains("<class name=\"android/media/MediaFeature\" since=\"31\">"))

        // The filter only defines a single API in framework-connectivity: verify that only that API has the module/sdks attributes
        assertTrue(xml.contains("<class name=\"android/net/CaptivePortal\" module=\"framework-connectivity\" since=\"23\" sdks=\"30:1,0:23\">"))
        assertTrue(xml.contains("<class name=\"android/net/ConnectivityDiagnosticsManager\" since=\"30\">"))

        // The order of the SDKs should be respected
        // android.net.eap.EapAkaInfo    R S T -> 0,30,31,33
        assertTrue(xml.contains("<class name=\"android/net/eap/EapAkaInfo\" module=\"android.net.ipsec.ike\" since=\"33\" sdks=\"30:3,31:3,33:3,0:33\">"))
        // android.net.eap.EapInfo       T S R -> 0,33,31,30
        assertTrue(xml.contains("<class name=\"android/net/eap/EapInfo\" module=\"android.net.ipsec.ike\" since=\"33\" sdks=\"33:3,31:3,30:3,0:33\">"))

        // Verify historical backfill
        assertEquals(30, apiLookup.getClassVersion("android/os/ext/SdkExtensions"))
        assertEquals(30, apiLookup.getMethodVersion("android/os/ext/SdkExtensions", "getExtensionVersion", "(I)I"))
        assertEquals(31, apiLookup.getMethodVersion("android/os/ext/SdkExtensions", "getAllExtensionVersions", "()Ljava/util/Map;"))

        // Verify there's no extension versions listed for SdkExtensions
        val sdkExtClassLine = xml.lines().first { it.contains("<class name=\"android/os/ext/SdkExtensions\"") }
        assertFalse(sdkExtClassLine.contains("sdks="))
    }

    @Test
    fun `Correct API Level for release`() {
        var oldSdkJars = File("prebuilts/tools/common/api-versions")
        if (!oldSdkJars.isDirectory) {
            oldSdkJars = File("../../prebuilts/tools/common/api-versions")
            if (!oldSdkJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found - is \$PWD set to an Android source tree?")
                return
            }
        }

        var platformJars = File("prebuilts/sdk")
        if (!platformJars.isDirectory) {
            platformJars = File("../../prebuilts/sdk")
            if (!platformJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found: $platformJars")
                return
            }
        }
        val output = File.createTempFile("api-info", "xml")
        output.deleteOnExit()
        val outputPath = output.path

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                outputPath,
                ARG_ANDROID_JAR_PATTERN,
                "${oldSdkJars.path}/android-%/android.jar",
                ARG_ANDROID_JAR_PATTERN,
                "${platformJars.path}/%/public/android.jar",
                ARG_CURRENT_CODENAME,
                "REL",
                ARG_CURRENT_VERSION,
                "89" // not real api level
            ),
            sourceFiles = arrayOf(
                java(
                    """
                        package android.pkg;
                        public class MyTest {
                        }
                        """
                )
            )
        )

        assertTrue(output.isFile)
        // Anything with a REL codename is in the current API level
        val xml = output.readText(UTF_8)
        assertTrue(xml.contains("<class name=\"android/pkg/MyTest\" since=\"89\""))
        val apiLookup = getApiLookup(output, temporaryFolder.newFolder())
        assertEquals(89, apiLookup.getClassVersion("android.pkg.MyTest"))
    }

    @Test
    fun `Correct API Level for non-release`() {
        var oldSdkJars = File("prebuilts/tools/common/api-versions")
        if (!oldSdkJars.isDirectory) {
            oldSdkJars = File("../../prebuilts/tools/common/api-versions")
            if (!oldSdkJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found - is \$PWD set to an Android source tree?")
                return
            }
        }

        var platformJars = File("prebuilts/sdk")
        if (!platformJars.isDirectory) {
            platformJars = File("../../prebuilts/sdk")
            if (!platformJars.isDirectory) {
                println("Ignoring ${ApiGeneratorTest::class.java}: prebuilts not found: $platformJars")
                return
            }
        }
        val output = File.createTempFile("api-info", "xml")
        output.deleteOnExit()
        val outputPath = output.path

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                outputPath,
                ARG_ANDROID_JAR_PATTERN,
                "${oldSdkJars.path}/android-%/android.jar",
                ARG_ANDROID_JAR_PATTERN,
                "${platformJars.path}/%/public/android.jar",
                ARG_CURRENT_CODENAME,
                "ZZZ", // not just Z, but very ZZZ
                ARG_CURRENT_VERSION,
                "89" // not real api level
            ),
            sourceFiles = arrayOf(
                java(
                    """
                        package android.pkg;
                        public class MyTest {
                        }
                        """
                )
            )
        )

        assertTrue(output.isFile)
        // Metalava should understand that a codename means "current api + 1"
        val xml = output.readText(UTF_8)
        assertTrue(xml.contains("<class name=\"android/pkg/MyTest\" since=\"90\""))
        val apiLookup = getApiLookup(output, temporaryFolder.newFolder())
        assertEquals(90, apiLookup.getClassVersion("android.pkg.MyTest"))
    }

    @Test
    fun `Generate API for test prebuilts`() {
        var testPrebuiltsRoot = File(System.getenv("METALAVA_TEST_PREBUILTS_SDK_ROOT"))
        if (!testPrebuiltsRoot.isDirectory) {
            fail("test prebuilts not found: $testPrebuiltsRoot")
        }

        val api_versions_xml = File.createTempFile("api-versions", "xml")
        api_versions_xml.deleteOnExit()

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                api_versions_xml.path,
                ARG_ANDROID_JAR_PATTERN,
                "${testPrebuiltsRoot.path}/%/public/android.jar",
                ARG_SDK_JAR_ROOT,
                "${testPrebuiltsRoot.path}/extensions",
                ARG_SDK_INFO_FILE,
                "${testPrebuiltsRoot.path}/sdk-extensions-info.xml",
                ARG_FIRST_VERSION,
                "30",
                ARG_CURRENT_VERSION,
                "32",
                ARG_CURRENT_CODENAME,
                "Foo"
            ),
            sourceFiles = arrayOf(
                java(
                    """
                    package android.test;
                    public class ClassAddedInApi31AndExt2 {
                        private ClassAddedInApi31AndExt2() {}
                        public static final int FIELD_ADDED_IN_API_31_AND_EXT_2 = 1;
                        public static final int FIELD_ADDED_IN_EXT_3 = 2;
                        public void methodAddedInApi31AndExt2() { throw new RuntimeException("Stub!"); }
                        public void methodAddedInExt3() { throw new RuntimeException("Stub!"); };
                        public void methodNotFinalized() { throw new RuntimeException("Stub!"); }
                    }
                    """
                )
            )
        )

        assertTrue(api_versions_xml.isFile)
        val xml = api_versions_xml.readText(UTF_8)

        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <api version="3" min="30">
                <sdk id="30" shortname="R-ext" name="R Extensions" reference="android/os/Build${'$'}VERSION_CODES${'$'}R"/>
                <sdk id="31" shortname="S-ext" name="S Extensions" reference="android/os/Build${'$'}VERSION_CODES${'$'}S"/>
                <class name="android/test/ClassAddedInApi30" since="30">
                    <extends name="java/lang/Object"/>
                    <method name="methodAddedInApi30()V"/>
                    <method name="methodAddedInApi31()V" since="31"/>
                </class>
                <class name="android/test/ClassAddedInApi31AndExt2" module="framework-ext" since="31" sdks="30:2,31:2,0:31">
                    <extends name="java/lang/Object"/>
                    <method name="methodAddedInApi31AndExt2()V"/>
                    <method name="methodAddedInExt3()V" since="33" sdks="30:3,31:3"/>
                    <method name="methodNotFinalized()V" since="33" sdks="0:33"/>
                    <field name="FIELD_ADDED_IN_API_31_AND_EXT_2"/>
                    <field name="FIELD_ADDED_IN_EXT_3" since="33" sdks="30:3,31:3"/>
                </class>
                <class name="android/test/ClassAddedInExt1" module="framework-ext" since="31" sdks="30:1,31:1,0:31">
                    <extends name="java/lang/Object"/>
                    <method name="methodAddedInApi31AndExt2()V" sdks="30:2,31:2,0:31"/>
                    <method name="methodAddedInExt1()V"/>
                    <method name="methodAddedInExt3()V" since="33" sdks="30:3,31:3"/>
                    <field name="FIELD_ADDED_IN_API_31_AND_EXT_2" sdks="30:2,31:2,0:31"/>
                    <field name="FIELD_ADDED_IN_EXT_1"/>
                    <field name="FIELD_ADDED_IN_EXT_3" since="33" sdks="30:3,31:3"/>
                </class>
                <class name="android/test/ClassAddedInExt3" module="framework-ext" since="33" sdks="30:3,31:3">
                    <extends name="java/lang/Object"/>
                    <method name="methodAddedInExt3()V"/>
                    <field name="FIELD_ADDED_IN_EXT_3"/>
                </class>
                <class name="java/lang/Object" since="30">
                    <method name="&lt;init>()V"/>
                </class>
            </api>
        """

        fun String.trimEachLine(): String =
            lines().map {
                it.trim()
            }.filter {
                it.isNotEmpty()
            }.joinToString("\n")

        assertEquals(expected.trimEachLine(), xml.trimEachLine())
    }

    @Test
    fun `Generate API while removing missing class references`() {
        val api_versions_xml = File.createTempFile("api-versions", "xml")
        api_versions_xml.deleteOnExit()

        check(
            extraArguments = arrayOf(
                ARG_GENERATE_API_LEVELS,
                api_versions_xml.path,
                ARG_REMOVE_MISSING_CLASS_REFERENCES_IN_API_LEVELS,
                ARG_FIRST_VERSION,
                "30",
                ARG_CURRENT_VERSION,
                "32",
                ARG_CURRENT_CODENAME,
                "Foo"
            ),
            sourceFiles = arrayOf(
                java(
                    """
                    package android.test;
                    public class ClassThatImplementsMethodFromApex implements ClassFromApex {
                    }
                    """
                )
            )
        )

        assertTrue(api_versions_xml.isFile)
        val xml = api_versions_xml.readText(UTF_8)

        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <api version="3" min="30">
                <class name="android/test/ClassThatImplementsMethodFromApex" since="33">
                    <method name="&lt;init>()V"/>
                </class>
            </api>
        """

        fun String.trimEachLine(): String =
            lines().map {
                it.trim()
            }.filter {
                it.isNotEmpty()
            }.joinToString("\n")

        assertEquals(expected.trimEachLine(), xml.trimEachLine())
    }

    @Test
    fun `Generate API finds missing class references`() {
        var testPrebuiltsRoot = File(System.getenv("METALAVA_TEST_PREBUILTS_SDK_ROOT"))
        if (!testPrebuiltsRoot.isDirectory) {
            fail("test prebuilts not found: $testPrebuiltsRoot")
        }

        val api_versions_xml = File.createTempFile("api-versions", "xml")
        api_versions_xml.deleteOnExit()

        var exception: IllegalStateException? = null
        try {
            check(
                extraArguments = arrayOf(
                    ARG_GENERATE_API_LEVELS,
                    api_versions_xml.path,
                    ARG_FIRST_VERSION,
                    "30",
                    ARG_CURRENT_VERSION,
                    "32",
                    ARG_CURRENT_CODENAME,
                    "Foo"
                ),
                sourceFiles = arrayOf(
                    java(
                        """
                        package android.test;
                        // Really this class should implement some interface that doesn't exist,
                        // but that's hard to set up in the test harness, so just verify that
                        // metalava complains about java/lang/Object not existing because we didn't
                        // include the testdata prebuilt jars.
                        public class ClassThatImplementsMethodFromApex {
                        }
                        """
                    )
                )
            )
        } catch (e: IllegalStateException) {
            exception = e
        }

        assertNotNull(exception)
        assertThat(exception?.message ?: "").contains("There are classes in this API that reference other classes that do not exist in this API.")
        assertThat(exception?.message ?: "").contains("java/lang/Object referenced by:\n    android/test/ClassThatImplementsMethodFromApex")
    }
}
