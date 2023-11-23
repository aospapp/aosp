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

package com.android.tools.metalava

import org.junit.Test

class ApiLintTest : DriverTest() {

    @Test
    fun `Test names`() {
        // Make sure we only flag issues in new API
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/ALL_CAPS.java:3: warning: Acronyms should not be capitalized in class names: was `ALL_CAPS`, should this be `AllCaps`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/HTMLWriter.java:3: warning: Acronyms should not be capitalized in class names: was `HTMLWriter`, should this be `HtmlWriter`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyStringImpl.java:3: error: Don't expose your implementation details: `MyStringImpl` ends with `Impl` [EndsWithImpl]
                src/android/pkg/badlyNamedClass.java:3: error: Class must start with uppercase char: badlyNamedClass [StartWithUpper] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/badlyNamedClass.java:5: error: Method name must start with lowercase char: BadlyNamedMethod1 [StartWithLower] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/badlyNamedClass.java:7: warning: Acronyms should not be capitalized in method names: was `fromHTMLToHTML`, should this be `fromHtmlToHtml`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/badlyNamedClass.java:8: warning: Acronyms should not be capitalized in method names: was `toXML`, should this be `toXml`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/badlyNamedClass.java:9: warning: Acronyms should not be capitalized in method names: was `getID`, should this be `getId`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/badlyNamedClass.java:4: error: Constant field names must be named with only upper case characters: `android.pkg.badlyNamedClass#BadlyNamedField`, should be `BADLY_NAMED_FIELD`? [AllUpper] [Rule C2 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class badlyNamedClass {
                        public static final int BadlyNamedField = 1;
                        public void BadlyNamedMethod1() { }

                        public void fromHTMLToHTML() { }
                        public void toXML() { }
                        public String getID() { return null; }
                        public void setZOrderOnTop() { } // OK
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class ALL_CAPS { // like android.os.Build.VERSION_CODES
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class HTMLWriter {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyStringImpl {
                    }
                    """
                ),
                java(
                    """
                    package android.icu;

                    // Same as above android.pkg.badlyNamedClass but in a package
                    // that API lint is supposed to ignore (see ApiLint#isInteresting)
                    public class badlyNamedClass {
                        public static final int BadlyNamedField = 1;
                        public void BadlyNamedMethod1() { }

                        public void toXML() { }
                        public String getID() { return null; }
                        public void setZOrderOnTop() { }
                    }
                    """
                ),
                java(
                    """
                    package android.icu.sub;

                    // Same as above android.pkg.badlyNamedClass but in a package
                    // that API lint is supposed to ignore (see ApiLint#isInteresting)
                    public class badlyNamedClass {
                        public static final int BadlyNamedField = 1;
                        public void BadlyNamedMethod1() { }

                        public void toXML() { }
                        public String getID() { return null; }
                        public void setZOrderOnTop() { }
                    }
                    """
                )
            ),
            expectedOutput = """
                9 new API lint issues were found.
                See tools/metalava/API-LINT.md for how to handle these.
            """
        )
    }

    @Test
    fun `Test names against previous API`() {
        check(
            apiLint = """
                package android.pkg {
                  public class badlyNamedClass {
                    ctor public badlyNamedClass();
                    method public void BadlyNamedMethod1();
                    method public void fromHTMLToHTML();
                    method public String getID();
                    method public void toXML();
                    field public static final int BadlyNamedField = 1; // 0x1
                  }
                }
            """.trimIndent(),
            compatibilityMode = false,
            warnings = """
                src/android/pkg/badlyNamedClass.java:8: warning: Acronyms should not be capitalized in method names: was `toXML2`, should this be `toXmL2`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg2/HTMLWriter.java:3: warning: Acronyms should not be capitalized in class names: was `HTMLWriter`, should this be `HtmlWriter`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                src/android/pkg2/HTMLWriter.java:4: warning: Acronyms should not be capitalized in method names: was `fromHTMLToHTML`, should this be `fromHtmlToHtml`? [AcronymName] [Rule S1 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class badlyNamedClass {
                        public static final int BadlyNamedField = 1;

                        public void fromHTMLToHTML() { }
                        public void toXML() { }
                        public void toXML2() { }
                        public String getID() { return null; }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg2;

                    public class HTMLWriter {
                        public void fromHTMLToHTML() { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test constants`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/Constants.java:9: error: All constants must be defined at compile time: android.pkg.Constants#FOO [CompileTimeConstant]
                src/android/pkg/Constants.java:8: warning: If min/max could change in future, make them dynamic methods: android.pkg.Constants#MAX_FOO [MinMaxConstant] [Rule C8 in go/android-api-guidelines]
                src/android/pkg/Constants.java:7: warning: If min/max could change in future, make them dynamic methods: android.pkg.Constants#MIN_FOO [MinMaxConstant] [Rule C8 in go/android-api-guidelines]
                src/android/pkg/Constants.java:6: error: Constant field names must be named with only upper case characters: `android.pkg.Constants#myStrings`, should be `MY_STRINGS`? [AllUpper] [Rule C2 in go/android-api-guidelines]
                src/android/pkg/Constants.java:5: error: Constant field names must be named with only upper case characters: `android.pkg.Constants#strings`, should be `STRINGS`? [AllUpper] [Rule C2 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class Constants {
                        private Constants() { }
                        public static final String[] strings = { "NONE", "WPA_PSK" };
                        public static final String[] myStrings = { "NONE", "WPA_PSK" };
                        public static final int MIN_FOO = 1;
                        public static final int MAX_FOO = 10;
                        public static final String FOO = System.getProperty("foo");
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `No enums`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyEnum.java:3: error: Enums are discouraged in Android APIs [Enum] [Rule F5 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public enum MyEnum {
                       FOO, BAR
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test callbacks`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyCallback.java:3: error: Callback method names must follow the on<Something> style: bar [CallbackMethodName] [Rule L1 in go/android-api-guidelines]
                src/android/pkg/MyCallbacks.java:3: error: Callback class names should be singular: MyCallbacks [SingularCallback] [Rule L1 in go/android-api-guidelines]
                src/android/pkg/MyInterfaceCallback.java:3: error: Callbacks must be abstract class instead of interface to enable extension in future API levels: MyInterfaceCallback [CallbackInterface] [Rule CL3 in go/android-api-guidelines]
                src/android/pkg/MyObserver.java:3: warning: Class should be named MyCallback [CallbackName] [Rule L1 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyCallbacks {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyObserver {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public interface MyInterfaceCallback {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyCallback {
                        public void onFoo() {
                        }
                        public void onAnimationStart() {
                        }
                        public void bar() {
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test listeners`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyCallback.java:3: error: Callback method names must follow the on<Something> style: bar [CallbackMethodName] [Rule L1 in go/android-api-guidelines]
                src/android/pkg/MyClassListener.java:3: error: Listeners should be an interface, or otherwise renamed Callback: MyClassListener [ListenerInterface] [Rule L1 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class MyClassListener {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public interface OnFooBarListener {
                        void bar();
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public interface OnFooBarListener {
                        void onFooBar(); // OK
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public interface MyInterfaceListener {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyCallback {
                        public void onFoo() {
                        }
                        public void bar() {
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test actions`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/accounts/Actions.java:7: error: Intent action constant name must be ACTION_FOO: ACCOUNT_ADDED [IntentName] [Rule C3 in go/android-api-guidelines]
                src/android/accounts/Actions.java:6: error: Inconsistent action value; expected `android.accounts.action.ACCOUNT_OPENED`, was `android.accounts.ACCOUNT_OPENED` [ActionValue] [Rule C4 in go/android-api-guidelines]
                src/android/accounts/Actions.java:8: error: Intent action constant name must be ACTION_FOO: SOMETHING [IntentName] [Rule C3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.accounts;

                    public class Actions {
                        private Actions() { }
                        public static final String ACTION_ACCOUNT_REMOVED = "android.accounts.action.ACCOUNT_REMOVED";
                        public static final String ACTION_ACCOUNT_OPENED = "android.accounts.ACCOUNT_OPENED";
                        public static final String ACCOUNT_ADDED = "android.accounts.action.ACCOUNT_ADDED";
                        public static final String SOMETHING = "android.accounts.action.ACCOUNT_MOVED";
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test extras`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/accounts/Extras.java:5: error: Inconsistent extra value; expected `android.accounts.extra.AUTOMATIC_RULE_ID`, was `android.app.extra.AUTOMATIC_RULE_ID` [ActionValue] [Rule C4 in go/android-api-guidelines]
                src/android/accounts/Extras.java:7: error: Intent extra constant name must be EXTRA_FOO: RULE_ID [IntentName] [Rule C3 in go/android-api-guidelines]
                src/android/accounts/Extras.java:6: error: Intent extra constant name must be EXTRA_FOO: SOMETHING_EXTRA [IntentName] [Rule C3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.accounts;

                    public class Extras {
                        private Extras() { }
                        public static final String EXTRA_AUTOMATIC_RULE_ID = "android.app.extra.AUTOMATIC_RULE_ID";
                        public static final String SOMETHING_EXTRA = "something.here";
                        public static final String RULE_ID = "android.app.extra.AUTOMATIC_RULE_ID";
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test equals and hashCode`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MissingEquals.java:4: error: Must override both equals and hashCode; missing one in android.pkg.MissingEquals [EqualsAndHashCode] [Rule M8 in go/android-api-guidelines]
                src/android/pkg/MissingHashCode.java:5: error: Must override both equals and hashCode; missing one in android.pkg.MissingHashCode [EqualsAndHashCode] [Rule M8 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
                    public class Ok {
                        public boolean equals(Object other) { return true; }
                        public int hashCode() { return 0; }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MissingEquals {
                        public int hashCode() { return 0; }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
                    public class MissingHashCode {
                        public boolean equals(Object other) { return true; }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class UnrelatedEquals {
                        @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
                        public static boolean equals(Object other) { return true; } // static
                        public boolean equals(int other) { return false; } // wrong parameter type
                        public boolean equals(Object other, int bar) { return false; } // wrong signature
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Test Parcelable`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MissingCreator.java:3: error: Parcelable requires a `CREATOR` field; missing in android.pkg.MissingCreator [ParcelCreator] [Rule FW3 in go/android-api-guidelines]
                src/android/pkg/MissingDescribeContents.java:3: error: Parcelable requires `public int describeContents()`; missing in android.pkg.MissingDescribeContents [ParcelCreator] [Rule FW3 in go/android-api-guidelines]
                src/android/pkg/MissingWriteToParcel.java:3: error: Parcelable requires `void writeToParcel(Parcel, int)`; missing in android.pkg.MissingWriteToParcel [ParcelCreator] [Rule FW3 in go/android-api-guidelines]
                src/android/pkg/NonFinalParcelable.java:3: error: Parcelable classes must be final: android.pkg.NonFinalParcelable is not final [ParcelNotFinal] [Rule FW8 in go/android-api-guidelines]
                src/android/pkg/ParcelableConstructor.java:4: error: Parcelable inflation is exposed through CREATOR, not raw constructors, in android.pkg.ParcelableConstructor [ParcelConstructor] [Rule FW3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public final class ParcelableConstructor implements android.os.Parcelable {
                        public ParcelableConstructor(android.os.Parcel p) { }
                        public int describeContents() { return 0; }
                        public void writeToParcel(android.os.Parcel p, int f) { }
                        public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR = null;
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class NonFinalParcelable implements android.os.Parcelable {
                        public NonFinalParcelable() { }
                        public int describeContents() { return 0; }
                        public void writeToParcel(android.os.Parcel p, int f) { }
                        public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR = null;
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public final class MissingCreator implements android.os.Parcelable {
                        public MissingCreator() { }
                        public int describeContents() { return 0; }
                        public void writeToParcel(android.os.Parcel p, int f) { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public final class MissingDescribeContents implements android.os.Parcelable {
                        public MissingDescribeContents() { }
                        public void writeToParcel(android.os.Parcel p, int f) { }
                        public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR = null;
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public final class MissingWriteToParcel implements android.os.Parcelable {
                        public MissingWriteToParcel() { }
                        public int describeContents() { return 0; }
                        public static final android.os.Parcelable.Creator<android.app.WallpaperColors> CREATOR = null;
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `No protected methods or fields are allowed`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:6: error: Protected methods not allowed; must be public: method android.pkg.MyClass.wrong()} [ProtectedMember] [Rule M7 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:8: error: Protected fields not allowed; must be public: field android.pkg.MyClass.wrong} [ProtectedMember] [Rule M7 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class MyClass implements AutoCloseable {
                        public void ok() { }
                        protected void finalize() { } // OK
                        protected void wrong() { }
                        public int ok = 42;
                        protected int wrong = 5;
                        private int ok2 = 2;
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Ensure registration methods are matched`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/RegistrationMethods.java:6: error: Found registerUnpairedCallback but not unregisterUnpairedCallback in android.pkg.RegistrationMethods [PairedRegistration] [Rule L2 in go/android-api-guidelines]
                src/android/pkg/RegistrationMethods.java:7: error: Found unregisterMismatchedCallback but not registerMismatchedCallback in android.pkg.RegistrationMethods [PairedRegistration] [Rule L2 in go/android-api-guidelines]
                src/android/pkg/RegistrationMethods.java:8: error: Callback methods should be named register/unregister; was addCallback [RegistrationName] [Rule L3 in go/android-api-guidelines]
                src/android/pkg/RegistrationMethods.java:13: error: Found addUnpairedListener but not removeUnpairedListener in android.pkg.RegistrationMethods [PairedRegistration] [Rule L2 in go/android-api-guidelines]
                src/android/pkg/RegistrationMethods.java:14: error: Found removeMismatchedListener but not addMismatchedListener in android.pkg.RegistrationMethods [PairedRegistration] [Rule L2 in go/android-api-guidelines]
                src/android/pkg/RegistrationMethods.java:15: error: Listener methods should be named add/remove; was registerWrongListener [RegistrationName] [Rule L3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class RegistrationMethods {
                        public void registerOkCallback(Runnable r) { } // OK
                        public void unregisterOkCallback(Runnable r) { } // OK
                        public void registerUnpairedCallback(Runnable r) { }
                        public void unregisterMismatchedCallback(Runnable r) { }
                        public void addCallback(Runnable r) { }

                        public void addOkListener(Runnable r) { } // OK
                        public void removeOkListener(Runnable r) { } // OK

                        public void addUnpairedListener(Runnable r) { }
                        public void removeMismatchedListener(Runnable r) { }
                        public void registerWrongListener(Runnable r) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Api methods should not be synchronized in their signature`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/CheckSynchronization.java:10: error: Internal locks must not be exposed: method android.pkg.CheckSynchronization.errorMethod1(Runnable) [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization.java:12: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization.errorMethod2() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization.java:16: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization.errorMethod2() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization.java:21: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization.errorMethod3() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization2.kt:5: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization2.errorMethod1() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization2.kt:8: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization2.errorMethod2() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization2.kt:16: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization2.errorMethod4() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                src/android/pkg/CheckSynchronization2.kt:18: error: Internal locks must not be exposed (synchronizing on this or class is still externally observable): method android.pkg.CheckSynchronization2.errorMethod5() [VisiblySynchronized] [Rule M5 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class CheckSynchronization {
                        public void okMethod1(Runnable r) { }
                        private static final Object LOCK = new Object();
                        public void okMethod2() {
                            synchronized(LOCK) {
                            }
                        }
                        public synchronized void errorMethod1(Runnable r) { } // ERROR
                        public void errorMethod2() {
                            synchronized(this) {
                            }
                        }
                        public void errorMethod2() {
                            synchronized(CheckSynchronization.class) {
                            }
                        }
                        public void errorMethod3() {
                            if (true) {
                                synchronized(CheckSynchronization.class) {
                                }
                            }
                        }
                    }
                    """
                ),
                kotlin(
                    """
                    package android.pkg;

                    class CheckSynchronization2 {
                        fun errorMethod1() {
                            synchronized(this) { println("hello") }
                        }
                        fun errorMethod2() {
                            synchronized(CheckSynchronization2::class.java) { println("hello") }
                        }
                        fun errorMethod3() {
                            @Suppress("ConstantConditionIf")
                            if (true) {
                                synchronized(CheckSynchronization2::class.java) { println("hello") }
                            }
                        }
                        fun errorMethod4() = synchronized(this) { println("hello") }
                        fun errorMethod5() {
                            synchronized(CheckSynchronization2::class) { println("hello") }
                        }
                        fun okMethod() {
                            val lock = Object()
                            synchronized(lock) { println("hello") }
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check intent builder names`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/IntentBuilderNames.java:6: warning: Methods creating an Intent should be named `create<Foo>Intent()`, was `makeMyIntent` [IntentBuilderName] [Rule FW1 in go/android-api-guidelines]
                src/android/pkg/IntentBuilderNames.java:7: warning: Methods creating an Intent should be named `create<Foo>Intent()`, was `createIntentNow` [IntentBuilderName] [Rule FW1 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.content.Intent;

                    public class IntentBuilderNames {
                        public Intent createEnrollIntent() { return null; } // OK
                        public Intent makeMyIntent() { return null; } // WARN
                        public Intent createIntentNow() { return null; } // WARN
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check helper classes`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass1.java:3: error: Inconsistent class name; should be `<Foo>Activity`, was `MyClass1` [ContextNameSuffix] [Rule C4 in go/android-api-guidelines]
                src/android/pkg/MyClass1.java:6: warning: Methods implemented by developers should follow the on<Something> style, was `badlyNamedAbstractMethod` [OnNameExpected]
                src/android/pkg/MyClass1.java:7: warning: If implemented by developer, should follow the on<Something> style; otherwise consider marking final [OnNameExpected]
                src/android/pkg/MyClass1.java:3: error: MyClass1 should not extend `Activity`. Activity subclasses are impossible to compose. Expose a composable API instead. [ForbiddenSuperClass]
                src/android/pkg/MyClass2.java:3: error: Inconsistent class name; should be `<Foo>Provider`, was `MyClass2` [ContextNameSuffix] [Rule C4 in go/android-api-guidelines]
                src/android/pkg/MyClass3.java:3: error: Inconsistent class name; should be `<Foo>Service`, was `MyClass3` [ContextNameSuffix] [Rule C4 in go/android-api-guidelines]
                src/android/pkg/MyClass4.java:3: error: Inconsistent class name; should be `<Foo>Receiver`, was `MyClass4` [ContextNameSuffix] [Rule C4 in go/android-api-guidelines]
                src/android/pkg/MyOkActivity.java:3: error: MyOkActivity should not extend `Activity`. Activity subclasses are impossible to compose. Expose a composable API instead. [ForbiddenSuperClass]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass1 extends android.app.Activity {
                        public void onOk() { }
                        public final void ok() { }
                        public abstract void badlyNamedAbstractMethod();
                        public void badlyNamedMethod() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyClass2 extends android.content.ContentProvider {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyClass3 extends android.app.Service {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyClass4 extends android.content.BroadcastReceiver {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyOkActivity extends android.app.Activity {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check builders`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:4: warning: Methods must return the builder object (return type Builder instead of void): method android.pkg.MyClass.Builder.setSomething(int) [SetterReturnsThis] [Rule M4 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:4: warning: Builder methods names should use setFoo() style: method android.pkg.MyClass.Builder.withFoo(int) [BuilderSetStyle]
                src/android/pkg/MyClass.java:4: warning: Missing `build()` method in android.pkg.MyClass.Builder [MissingBuild]
                src/android/pkg/TopLevelBuilder.java:3: warning: Builder should be defined as inner class: android.pkg.TopLevelBuilder [TopLevelBuilder]
                src/android/pkg/TopLevelBuilder.java:3: warning: Missing `build()` method in android.pkg.TopLevelBuilder [MissingBuild]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class TopLevelBuilder {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public class Builder {
                            public void clearAll() { }
                            public int getSomething() { return 0; }
                            public void setSomething(int s) { }
                            public Builder withFoo(int s) { return this; }
                            public Builder setOk(int s) { return this; }
                        }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class Ok {
                        public class OkBuilder {
                            public Ok build() { return null; }
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Raw AIDL`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass1.java:3: error: Raw AIDL interfaces must not be exposed: MyClass1 extends Binder [RawAidl]
                src/android/pkg/MyClass2.java:3: error: Raw AIDL interfaces must not be exposed: MyClass2 implements IInterface [RawAidl]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class MyClass1 extends android.os.Binder {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public abstract class MyClass2 implements android.os.IInterface {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;
                    // Ensure that we don't flag transitively implementing IInterface
                    public class MyClass3 extends MyClass1 implements MyClass2 {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Internal packages`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/com/android/pkg/MyClass.java:3: error: Internal classes must not be exposed [InternalClasses]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package com.android.pkg;

                    public class MyClass {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check package layering`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/content/MyClass1.java:7: warning: Field type `android.view.View` violates package layering: nothing in `package android.content` should depend on `package android.view` [PackageLayering] [Rule FW6 in go/android-api-guidelines]
                src/android/content/MyClass1.java:7: warning: Method return type `android.view.View` violates package layering: nothing in `package android.content` should depend on `package android.view` [PackageLayering] [Rule FW6 in go/android-api-guidelines]
                src/android/content/MyClass1.java:7: warning: Method parameter type `android.view.View` violates package layering: nothing in `package android.content` should depend on `package android.view` [PackageLayering] [Rule FW6 in go/android-api-guidelines]
                src/android/content/MyClass1.java:7: warning: Method parameter type `android.view.View` violates package layering: nothing in `package android.content` should depend on `package android.view` [PackageLayering] [Rule FW6 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.content;

                    import android.graphics.drawable.Drawable;
                    import android.graphics.Bitmap;
                    import android.view.View;

                    public class MyClass1 {
                        public View view = null;
                        public Drawable drawable = null;
                        public Bitmap bitmap = null;
                        public View ok(View view, Drawable drawable) { return null; }
                        public Bitmap wrong(View view, Bitmap bitmap) { return null; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check boolean getter and setter naming patterns`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:8: error: Symmetric method for `setProp4` must be named `getProp4`; was `isProp4` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:12: error: Symmetric method for `hasError1` must be named `setHasError1`; was `setError1` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:11: error: Symmetric method for `setError1` must be named `getError1`; was `hasError1` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:14: error: Symmetric method for `isError2` must be named `setIsError2`; was `setHasError2` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:18: error: Symmetric method for `getError3` must be named `setError3`; was `setIsError3` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:16: error: Symmetric method for `getError3` must be named `setError3`; was `setHasError3` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:20: error: Symmetric method for `hasError5` must be named `setHasError5`; was `setError5` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:19: error: Symmetric method for `setError5` must be named `getError5`; was `hasError5` [GetterSetterNames] [Rule M6 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public int getProp1() { return 0; }
                        public boolean getProp2() { return false; }
                        public boolean getProp3() { return false; }
                        public void setProp3(boolean s) { }
                        public boolean isProp4() { return false; }
                        public void setProp4(boolean s) { }

                        public boolean hasError1() { return false; }
                        public void setError1(boolean s) { }
                        public boolean isError2() { return false; }
                        public void setHasError2(boolean s) { }
                        public boolean getError3() { return false; }
                        public void setHasError3(boolean s) { }
                        public boolean isError4() { return false; }
                        public void setIsError3(boolean s) { }
                        public boolean hasError5() { return false; }
                        public void setError5(boolean s) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check banned collections`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:4: error: Parameter type is concrete collection (`java.util.HashMap`); must be higher-level interface [ConcreteCollection] [Rule CL2 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:5: error: Return type is concrete collection (`java.util.Vector`); must be higher-level interface [ConcreteCollection] [Rule CL2 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:5: error: Parameter type is concrete collection (`java.util.LinkedList`); must be higher-level interface [ConcreteCollection] [Rule CL2 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public MyClass(java.util.HashMap<String,String> map1, java.util.Map<String,String> map2) { }
                        public java.util.Vector<String> getList(java.util.LinkedList<String> list) { return null; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check non-overlapping flags`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/accounts/OverlappingFlags.java:19: warning: Found overlapping flag constant values: `TEST1_FLAG_SECOND` with value 3 (0x3) and overlapping flag value 1 (0x1) from `TEST1_FLAG_FIRST` [OverlappingConstants] [Rule C1 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.accounts;

                    public class OverlappingFlags {
                        private OverlappingFlags() { }
                        public static final int DRAG_FLAG_GLOBAL_PREFIX_URI_PERMISSION = 128; // 0x80
                        public static final int DRAG_FLAG_GLOBAL_URI_READ = 1; // 0x1
                        public static final int DRAG_FLAG_GLOBAL_URI_WRITE = 2; // 0x2
                        public static final int DRAG_FLAG_OPAQUE = 512; // 0x200
                        public static final int SYSTEM_UI_FLAG_FULLSCREEN = 4; // 0x4
                        public static final int SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2; // 0x2
                        public static final int SYSTEM_UI_FLAG_IMMERSIVE = 2048; // 0x800
                        public static final int SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096; // 0x1000
                        public static final int SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024; // 0x400
                        public static final int SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512; // 0x200
                        public static final int SYSTEM_UI_FLAG_LAYOUT_STABLE = 256; // 0x100
                        public static final int SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 16; // 0x10

                        public static final int TEST1_FLAG_FIRST = 1;
                        public static final int TEST1_FLAG_SECOND = 3;
                        public static final int TEST2_FLAG_FIRST = 5;
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check exception related issues`() {
        check(
            extraArguments = arrayOf(ARG_API_LINT,
                // Conflicting advice:
                ARG_HIDE, "BannedThrow"),
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:6: error: Methods must not throw generic exceptions (`java.lang.Exception`) [GenericException] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:7: error: Methods must not throw generic exceptions (`java.lang.Throwable`) [GenericException] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:8: error: Methods must not throw generic exceptions (`java.lang.Error`) [GenericException] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:9: warning: Methods taking no arguments should throw `IllegalStateException` instead of `java.lang.IllegalArgumentException` [IllegalStateException] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:10: warning: Methods taking no arguments should throw `IllegalStateException` instead of `java.lang.NullPointerException` [IllegalStateException] [Rule S1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:11: error: Methods calling into system server should rethrow `RemoteException` as `RuntimeException` (but do not list it in the throws clause) [RethrowRemoteException] [Rule FW9 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.os.RemoteException;

                    @SuppressWarnings("RedundantThrows")
                    public class MyClass {
                        public void method1() throws Exception { }
                        public void method2() throws Throwable { }
                        public void method3() throws Error { }
                        public void method4() throws IllegalArgumentException { }
                        public void method4() throws NullPointerException { }
                        public void method5() throws RemoteException { }
                        public void ok(int p) throws NullPointerException { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check no mentions of Google in APIs`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:4: error: Must never reference Google (`MyGoogleService`) [MentionsGoogle]
                src/android/pkg/MyClass.java:5: error: Must never reference Google (`callGoogle`) [MentionsGoogle]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public static class MyGoogleService {
                            public void callGoogle() { }
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check no usages of heavy BitSet`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:6: error: Type must not be heavy BitSet (method android.pkg.MyClass.reverse(java.util.BitSet)) [HeavyBitSet]
                src/android/pkg/MyClass.java:6: error: Type must not be heavy BitSet (parameter bitset in android.pkg.MyClass.reverse(java.util.BitSet bitset)) [HeavyBitSet]
                src/android/pkg/MyClass.java:5: error: Type must not be heavy BitSet (field android.pkg.MyClass.bitset) [HeavyBitSet]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import java.util.BitSet;

                    public class MyClass {
                        public BitSet bitset;
                        public BitSet reverse(BitSet bitset) { return null; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check Manager related issues`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyFirstManager.java:4: error: Managers must always be obtained from Context; no direct constructors [ManagerConstructor]
                src/android/pkg/MyFirstManager.java:6: error: Managers must always be obtained from Context (`get`) [ManagerLookup]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyFirstManager {
                        public MyFirstManager() {
                        }
                        public MyFirstManager get() { return null; }
                        public MySecondManager ok() { return null; }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MySecondManager {
                        private MySecondManager() {
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check boxed types`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:6: error: Must avoid boxed primitives (`java.lang.Long`) [AutoBoxing] [Rule M11 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:8: error: Must avoid boxed primitives (`java.lang.Short`) [AutoBoxing] [Rule M11 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:8: error: Must avoid boxed primitives (`java.lang.Double`) [AutoBoxing] [Rule M11 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:4: error: Must avoid boxed primitives (`java.lang.Integer`) [AutoBoxing] [Rule M11 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public Integer integer1;
                        public int integer2;
                        public MyClass(Long l) {
                        }
                        public Short getDouble(Double l) { return null; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check static utilities`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyUtils1.java:3: error: Fully-static utility classes must not have constructor [StaticUtils]
                src/android/pkg/MyUtils2.java:3: error: Fully-static utility classes must not have constructor [StaticUtils]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyUtils1 {
                        // implicit constructor
                        public static void foo() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyUtils2 {
                        public MyUtils2() { }
                        public static void foo() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyUtils3 {
                        private MyUtils3() { }
                        public static void foo() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyUtils4 {
                        // OK: instance method
                        public void foo() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyUtils5 {
                        // OK: instance field
                        public int foo = 42;
                        public static void foo() { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check context first`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:10: error: Context is distinct, so it must be the first argument (method `wrong`) [ContextFirst] [Rule M3 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:11: error: ContentResolver is distinct, so it must be the first argument (method `wrong`) [ContextFirst] [Rule M3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.content.Context;
                    import android.content.ContentResolver;

                    public class MyClass {
                        public MyClass(Context context1, Context context2) {
                        }
                        public void ok(ContentResolver resolver, int i) { }
                        public void ok(Context context, int i) { }
                        public void wrong(int i, Context context) { }
                        public void wrong(int i, ContentResolver resolver) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check listener last`() {
        check(
            extraArguments = arrayOf(ARG_API_LINT, ARG_HIDE, "ExecutorRegistration"),
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:6: warning: Listeners should always be at end of argument list (method `MyClass`) [ListenerLast] [Rule M3 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:9: warning: Listeners should always be at end of argument list (method `wrong`) [ListenerLast] [Rule M3 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.pkg.MyCallback;
                    import android.content.Context;

                    public class MyClass {
                        public MyClass(MyCallback listener, int i) {
                        }
                        public void ok(Context context, int i, MyCallback listener) { }
                        public void wrong(MyCallback listener, int i) { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    @SuppressWarnings("WeakerAccess")
                    public abstract class MyCallback {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check overloaded arguments`() {
        // TODO: This check is not yet hooked up
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        private MyClass() {
                        }

                        public void name1() { }
                        public void name1(int i) { }
                        public void name1(int i, int j) { }
                        public void name1(int i, int j, int k) { }
                        public void name1(int i, int j, int k, float f) { }

                        public void name2(int i) { }
                        public void name2(int i, int j) { }
                        public void name2(int i, float j, float k) { }
                        public void name2(int i, int j, int k, float f) { }
                        public void name2(int i, float f, int j) { }

                        public void name3() { }
                        public void name3(int i) { }
                        public void name3(int i, int j) { }
                        public void name3(int i, float j, int k) { }

                        public void name4(int i, int j, float f, long z) { }
                        public void name4(double d, int i, int j, float f) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check Callback Handlers`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:12: warning: Registration methods should have overload that accepts delivery Executor: `registerWrongCallback` [ExecutorRegistration] [Rule L1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:4: warning: Registration methods should have overload that accepts delivery Executor: `MyClass` [ExecutorRegistration] [Rule L1 in go/android-api-guidelines]
                src/android/pkg/MyClass.java:9: warning: SAM-compatible parameters (such as parameter 1, "executor", in android.pkg.MyClass.registerStreamEventCallback) should be last to improve Kotlin interoperability; see https://kotlinlang.org/docs/reference/java-interop.html#sam-conversions [SamShouldBeLast]
                src/android/pkg/MyClass.java:10: warning: SAM-compatible parameters (such as parameter 1, "executor", in android.pkg.MyClass.unregisterStreamEventCallback) should be last to improve Kotlin interoperability; see https://kotlinlang.org/docs/reference/java-interop.html#sam-conversions [SamShouldBeLast]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                        public MyClass(MyCallback callback) {
                        }

                        public void registerStreamEventCallback(MyCallback callback);
                        public void unregisterStreamEventCallback(MyCallback callback);
                        public void registerStreamEventCallback(java.util.concurrent.Executor executor, MyCallback callback);
                        public void unregisterStreamEventCallback(java.util.concurrent.Executor executor, MyCallback callback);

                        public void registerWrongCallback(MyCallback callback);
                        public void unregisterWrongCallback(MyCallback callback);
                    }
                    """
                ),
                java(
                    """
                    package android.graphics;
                    import android.pkg.MyCallback;

                    public class MyUiClass {
                        public void registerWrongCallback(MyCallback callback);
                        public void unregisterWrongCallback(MyCallback callback);
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    @SuppressWarnings("WeakerAccess")
                    public abstract class MyCallback {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check resource names`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/R.java:11: error: Expected resource name in `android.R.id` to be in the `fooBarBaz` style, was `wrong_style` [ResourceValueFieldName] [Rule C7 in go/android-api-guidelines]
                src/android/R.java:17: error: Expected config name to be in the `config_fooBarBaz` style, was `config_wrong_config_style` [ConfigFieldName]
                src/android/R.java:20: error: Expected resource name in `android.R.layout` to be in the `foo_bar_baz` style, was `wrongNameStyle` [ResourceFieldName]
                src/android/R.java:31: error: Expected resource name in `android.R.style` to be in the `FooBar_Baz` style, was `wrong_style_name` [ResourceStyleFieldName] [Rule C7 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android;

                    public final class R {
                        public static final class id {
                            public static final int text = 7000;
                            public static final int config_fooBar = 7001;
                            public static final int layout_fooBar = 7002;
                            public static final int state_foo = 7003;
                            public static final int foo = 7004;
                            public static final int fooBar = 7005;
                            public static final int wrong_style = 7006;
                        }
                        public static final class layout {
                            public static final int text = 7000;
                            public static final int config_fooBar = 7001;
                            public static final int config_foo = 7002;
                            public static final int config_wrong_config_style = 7003;

                            public static final int ok_name_style = 7004;
                            public static final int wrongNameStyle = 7005;
                        }
                        public static final class style {
                            public static final int TextAppearance_Compat_Notification = 0x7f0c00ec;
                            public static final int TextAppearance_Compat_Notification_Info = 0x7f0c00ed;
                            public static final int TextAppearance_Compat_Notification_Line2 = 0x7f0c00ef;
                            public static final int TextAppearance_Compat_Notification_Time = 0x7f0c00f2;
                            public static final int TextAppearance_Compat_Notification_Title = 0x7f0c00f4;
                            public static final int Widget_Compat_NotificationActionContainer = 0x7f0c015d;
                            public static final int Widget_Compat_NotificationActionText = 0x7f0c015e;

                            public static final int wrong_style_name = 7000;
                        }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check files`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/CheckFiles.java:12: warning: Methods accepting `File` should also accept `FileDescriptor` or streams: method android.pkg.CheckFiles.error(int,java.io.File) [StreamFiles] [Rule M10 in go/android-api-guidelines]
                src/android/pkg/CheckFiles.java:8: warning: Methods accepting `File` should also accept `FileDescriptor` or streams: constructor android.pkg.CheckFiles(android.content.Context,java.io.File) [StreamFiles] [Rule M10 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.content.Context;
                    import android.content.ContentResolver;
                    import java.io.File;
                    import java.io.InputStream;

                    public class CheckFiles {
                        public CheckFiles(Context context, File file) {
                        }
                        public void ok(int i, File file) { }
                        public void ok(int i, InputStream stream) { }
                        public void error(int i, File file) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check parcelable lists`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/CheckFiles.java:12: warning: Methods accepting `File` should also accept `FileDescriptor` or streams: method android.pkg.CheckFiles.error(int,java.io.File) [StreamFiles] [Rule M10 in go/android-api-guidelines]
                src/android/pkg/CheckFiles.java:8: warning: Methods accepting `File` should also accept `FileDescriptor` or streams: constructor android.pkg.CheckFiles(android.content.Context,java.io.File) [StreamFiles] [Rule M10 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.content.Context;
                    import android.content.ContentResolver;
                    import java.io.File;
                    import java.io.InputStream;

                    public class CheckFiles {
                        public CheckFiles(Context context, File file) {
                        }
                        public void ok(int i, File file) { }
                        public void ok(int i, InputStream stream) { }
                        public void error(int i, File file) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check abstract inner`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyManager.java:9: warning: Abstract inner classes should be static to improve testability: class android.pkg.MyManager.MyInnerManager [AbstractInner]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.content.Context;
                    import android.content.ContentResolver;
                    import java.io.File;
                    import java.io.InputStream;

                    public abstract class MyManager {
                         private MyManager() {}
                         public abstract class MyInnerManager {
                             private MyInnerManager() {}
                         }
                         public abstract static class MyOkManager {
                             private MyOkManager() {}
                         }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check for banned runtime exceptions`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:7: error: Methods must not mention RuntimeException subclasses in throws clauses (was `java.lang.SecurityException`) [BannedThrow]
                src/android/pkg/MyClass.java:6: error: Methods must not mention RuntimeException subclasses in throws clauses (was `java.lang.ClassCastException`) [BannedThrow]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass {
                         private MyClass() throws NullPointerException {} // OK, private
                         @SuppressWarnings("RedundantThrows") public MyClass(int i) throws java.io.IOException {} // OK, not runtime exception
                         public MyClass(double l) throws ClassCastException {} // error
                         public void foo() throws SecurityException {} // error
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check for extending errors`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyClass.java:3: error: Trouble must be reported through an `Exception`, not an `Error` (`MyClass` extends `Error`) [ExtendsError]
                src/android/pkg/MySomething.java:3: error: Exceptions must be named `FooException`, was `MySomething` [ExceptionName]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MyClass extends Error {
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MySomething extends RuntimeException {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check units and method names`() {
        check(
            extraArguments = arrayOf(ARG_API_LINT, ARG_HIDE, "NoByteOrShort"),
            compatibilityMode = false,
            warnings = """
                src/android/pkg/UnitNameTest.java:5: error: Expected method name units to be `Hours`, was `Hr` in `getErrorHr` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:6: error: Expected method name units to be `Nanos`, was `Ns` in `getErrorNs` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:7: error: Expected method name units to be `Bytes`, was `Byte` in `getErrorByte` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:8: error: Returned time values are strongly encouraged to be in milliseconds unless you need the extra precision, was `getErrorNanos` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:9: error: Returned time values are strongly encouraged to be in milliseconds unless you need the extra precision, was `getErrorMicros` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:10: error: Returned time values must be in milliseconds, was `getErrorSeconds` [MethodNameUnits]
                src/android/pkg/UnitNameTest.java:16: error: Fractions must use floats, was `int` in `getErrorFraction` [FractionFloat]
                src/android/pkg/UnitNameTest.java:17: error: Fractions must use floats, was `int` in `setErrorFraction` [FractionFloat]
                src/android/pkg/UnitNameTest.java:21: error: Percentage must use ints, was `float` in `getErrorPercentage` [PercentageInt]
                src/android/pkg/UnitNameTest.java:22: error: Percentage must use ints, was `float` in `setErrorPercentage` [PercentageInt]
                src/android/pkg/UnitNameTest.java:24: error: Expected method name units to be `Bytes`, was `Byte` in `readSingleByte` [MethodNameUnits]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class UnitNameTest {
                        public int okay() { return 0; }
                        public int getErrorHr() { return 0; }
                        public int getErrorNs() { return 0; }
                        public short getErrorByte() { return (short)0; }
                        public int getErrorNanos() { return 0; }
                        public long getErrorMicros() { return 0L; }
                        public long getErrorSeconds() { return 0L; }
                        public float getErrorSeconds() { return 0; }

                        public float getOkFraction() { return 0f; }
                        public void setOkFraction(float f) { }
                        public void setOkFraction(int n, int d) { }
                        public int getErrorFraction() { return 0; }
                        public void setErrorFraction(int i) { }

                        public int getOkPercentage() { return 0f; }
                        public void setOkPercentage(int i) { }
                        public float getErrorPercentage() { return 0f; }
                        public void setErrorPercentage(float f) { }

                        public int readSingleByte() { return 0; }

                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check closeable`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyErrorClass1.java:3: warning: Classes that release resources should implement AutoClosable and CloseGuard: class android.pkg.MyErrorClass1 [NotCloseable]
                src/android/pkg/MyErrorClass2.java:3: warning: Classes that release resources should implement AutoClosable and CloseGuard: class android.pkg.MyErrorClass2 [NotCloseable]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class MyOkClass1 implements java.io.Closeable {
                        public void close() {}
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    // Ok: indirectly implementing AutoCloseable
                    public abstract class MyOkClass2 implements MyInterface {
                        public void close() {}
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public class MyInterface extends AutoCloseable {
                        public void close() {}
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public abstract class MyErrorClass1 {
                        public void close() {}
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public abstract class MyErrorClass2 {
                        public void shutdown() {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check Kotlin keywords`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/KotlinKeywordTest.java:7: error: Avoid method names that are Kotlin hard keywords ("fun"); see https://android.github.io/kotlin-guides/interop.html#no-hard-keywords [KotlinKeyword]
                src/android/pkg/KotlinKeywordTest.java:8: error: Avoid field names that are Kotlin hard keywords ("as"); see https://android.github.io/kotlin-guides/interop.html#no-hard-keywords [KotlinKeyword]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class KotlinKeywordTest {
                        public void okay();
                        public int okay = 0;

                        public void fun() {} // error
                        public int as = 0; // error
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check Kotlin operators`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/KotlinOperatorTest.java:4: info: Method can be invoked with an indexing operator from Kotlin: `get` (this is usually desirable; just make sure it makes sense for this type of object) [KotlinOperator]
                src/android/pkg/KotlinOperatorTest.java:5: info: Method can be invoked with an indexing operator from Kotlin: `set` (this is usually desirable; just make sure it makes sense for this type of object) [KotlinOperator]
                src/android/pkg/KotlinOperatorTest.java:6: info: Method can be invoked with function call syntax from Kotlin: `invoke` (this is usually desirable; just make sure it makes sense for this type of object) [KotlinOperator]
                src/android/pkg/KotlinOperatorTest.java:7: info: Method can be invoked as a binary operator from Kotlin: `plus` (this is usually desirable; just make sure it makes sense for this type of object) [KotlinOperator]
                src/android/pkg/KotlinOperatorTest.java:7: error: Only one of `plus` and `plusAssign` methods should be present for Kotlin [UniqueKotlinOperator]
                src/android/pkg/KotlinOperatorTest.java:8: info: Method can be invoked as a compound assignment operator from Kotlin: `plusAssign` (this is usually desirable; just make sure it makes sense for this type of object) [KotlinOperator]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class KotlinOperatorTest {
                        public int get(int i) { return i + 2; }
                        public void set(int i, int j, int k) { }
                        public void invoke(int i, int j, int k) { }
                        public int plus(JavaClass other) { return 0; }
                        public void plusAssign(JavaClass other) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Return collections instead of arrays`() {
        check(
            extraArguments = arrayOf(ARG_API_LINT, ARG_HIDE, "AutoBoxing"),
            compatibilityMode = false,
            warnings = """
                src/android/pkg/ArrayTest.java:7: warning: Method should return Collection<Object> (or subclass) instead of raw array; was `java.lang.Object[]` [ArrayReturn]
                src/android/pkg/ArrayTest.java:8: warning: Method parameter should be Collection<Number> (or subclass) instead of raw array; was `java.lang.Number[]` [ArrayReturn]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class ArrayTest {
                        public int[] ok1() { return null; }
                        public String[] ok2() { return null; }
                        public void ok3(int[] i) { }
                        public Object[] error1() { return null; }
                        public void error2(Number[] i) { }
                        public void ok(Number... args) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check user handle names`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MyManager.java:6: warning: When a method overload is needed to target a specific UserHandle, callers should be directed to use Context.createPackageContextAsUser() and re-obtain the relevant Manager, and no new API should be added [UserHandle]
                src/android/pkg/UserHandleTest.java:7: warning: Method taking UserHandle should be named `doFooAsUser` or `queryFooForUser`, was `error` [UserHandleName]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    import android.os.UserHandle;

                    public class UserHandleTest {
                        public void doFooAsUser(int i, UserHandle handle) {} //OK
                        public void doFooForUser(int i, UserHandle handle) {} //OK
                        public void error(int i, UserHandle handle) {}
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;
                    import android.os.UserHandle;

                    public class MyManager {
                        private MyManager() { }
                        public void error(int i, UserHandle handle) {}
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check parameters`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/FooOptions.java:3: warning: Classes holding a set of parameters should be called `FooParams`, was `FooOptions` [UserHandleName]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class FooOptions {
                    }
                    """
                ),
                java(
                    """
                    package android.app;

                    public class ActivityOptions {
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check service names`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/ServiceNameTest.java:6: error: Inconsistent service value; expected `OTHER`, was `something` [ServiceName] [Rule C4 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class ServiceNameTest {
                        private ServiceNameTest() { }
                        public static final String FOO_BAR_SERVICE = "foo_bar";
                        public static final String OTHER_SERVICE = "something";
                        public static final int NON_STRING_SERVICE = 42;
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check method name tense`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MethodNameTest.java:6: warning: Unexpected tense; probably meant `enabled`, was `fooEnable` [MethodNameTense]
                src/android/pkg/MethodNameTest.java:7: warning: Unexpected tense; probably meant `enabled`, was `mustEnable` [MethodNameTense]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class MethodNameTest {
                        private MethodNameTest() { }
                        public void enable() { } // ok, not Enable
                        public void fooEnable() { } // warn
                        public boolean mustEnable() { return true; } // warn
                        public boolean isEnabled() { return true; }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check no clone`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/CloneTest.java:5: error: Provide an explicit copy constructor instead of implementing `clone()` [NoClone]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public class CloneTest {
                        public void clone(int i) { } // ok
                        public CloneTest clone() { return super.clone(); } // error
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check ICU types`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/IcuTest.java:4: warning: Type `java.util.TimeZone` should be replaced with richer ICU type `android.icu.util.TimeZone` [UseIcu]
                src/android/pkg/IcuTest.java:5: warning: Type `java.text.BreakIterator` should be replaced with richer ICU type `android.icu.text.BreakIterator` [UseIcu]
                src/android/pkg/IcuTest.java:5: warning: Type `java.text.Collator` should be replaced with richer ICU type `android.icu.text.Collator` [UseIcu]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class IcuTest {
                        public IcuTest(java.util.TimeZone timeZone) { }
                        public abstract java.text.BreakIterator foo(java.text.Collator collator);
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check using parcel file descriptors`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/PdfTest.java:4: error: Must use ParcelFileDescriptor instead of FileDescriptor in parameter fd in android.pkg.PdfTest.error1(java.io.FileDescriptor fd) [NoClone]
                src/android/pkg/PdfTest.java:5: error: Must use ParcelFileDescriptor instead of FileDescriptor in method android.pkg.PdfTest.getFileDescriptor() [UseParcelFileDescriptor] [Rule FW11 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class PdfTest {
                        public void error1(java.io.FileDescriptor fd) { }
                        public int getFileDescriptor() { return -1; }
                        public void ok(android.os.ParcelFileDescriptor fd) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check using bytes and shorts`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/ByteTest.java:4: warning: Should avoid odd sized primitives; use `int` instead of `byte` in parameter b in android.pkg.ByteTest.error1(byte b) [NoByteOrShort] [Rule FW12 in go/android-api-guidelines]
                src/android/pkg/ByteTest.java:5: warning: Should avoid odd sized primitives; use `int` instead of `short` in parameter s in android.pkg.ByteTest.error2(short s) [NoByteOrShort] [Rule FW12 in go/android-api-guidelines]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class ByteTest {
                        public void error1(byte b) { }
                        public void error2(short s) { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check singleton constructors`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/MySingleton.java:5: error: Singleton classes should use `getInstance()` methods: `MySingleton` [SingletonConstructor]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;

                    public abstract class MySingleton {
                        public static MySingleton getMyInstance() { return null; }
                        public MySingleton() { }
                        public void foo() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;

                    public abstract class MySingleton2 {
                        public static MySingleton2 getMyInstance() { return null; }
                        private MySingleton2() { } // OK, private
                        public void foo() { }
                    }
                    """
                )
            )
        )
    }

    @Test
    fun `Check forbidden super-classes`() {
        check(
            apiLint = "", // enabled
            compatibilityMode = false,
            warnings = """
                src/android/pkg/FirstActivity.java:2: error: FirstActivity should not extend `Activity`. Activity subclasses are impossible to compose. Expose a composable API instead. [ForbiddenSuperClass]
                src/android/pkg/IndirectActivity.java:2: error: IndirectActivity should not extend `Activity`. Activity subclasses are impossible to compose. Expose a composable API instead. [ForbiddenSuperClass]
                src/android/pkg/MyTask.java:2: error: MyTask should not extend `AsyncTask`. AsyncTask is an implementation detail. Expose a listener or, in androidx, a `ListenableFuture` API instead [ForbiddenSuperClass]
                """,
            sourceFiles = *arrayOf(
                java(
                    """
                    package android.pkg;
                    public abstract class FirstActivity extends android.app.Activity {
                        private FirstActivity() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;
                    public abstract class IndirectActivity extends android.app.ListActivity {
                        private IndirectActivity() { }
                    }
                    """
                ),
                java(
                    """
                    package android.pkg;
                    public abstract class MyTask extends android.os.AsyncTask<String,String,String> {
                        private MyTask() { }
                    }
                    """
                )
            )
        )
    }
}
