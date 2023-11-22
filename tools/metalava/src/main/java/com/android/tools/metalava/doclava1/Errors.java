/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.metalava.doclava1;

import com.android.tools.metalava.Severity;
import com.google.common.base.Splitter;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.android.sdklib.SdkVersionInfo.underlinesToCamelCase;
import static com.android.tools.metalava.Severity.ERROR;
import static com.android.tools.metalava.Severity.HIDDEN;
import static com.android.tools.metalava.Severity.INFO;
import static com.android.tools.metalava.Severity.INHERIT;
import static com.android.tools.metalava.Severity.LINT;
import static com.android.tools.metalava.Severity.WARNING;

// Copied from doclava1 (and a bunch of stuff left alone; preserving to have same error id's)
public class Errors {
    // Consider renaming to Issue; "Error" is special in Kotlin, and what does it mean for
    // an "error" to have severity "warning" ? The severity shouldn't be implied by the name.
    public static class Error {
        public final int code;
        @Nullable
        String fieldName;

        private Severity level;
        private final Severity defaultLevel;
        boolean setByUser;

        /**
         * The name of this error if known
         */
        @Nullable
        public String name;

        /**
         * When {@code level} is set to {@link Severity#INHERIT}, this is the parent from
         * which the error will inherit its level.
         */
        private final Error parent;

        /** Related rule, if any */
        public final String rule;

        /** Related explanation, if any */
        public final String explanation;

        /** Applicable category */
        public final Category category;

        private Error(int code, Severity level) {
            this(code, level, Category.UNKNOWN);
        }

        private Error(int code, Severity level, Category category) {
            this(code, level, null, category, null, null);
        }

        private Error(int code, Severity level, Category category, String rule) {
            this(code, level, null, category, rule, null);
        }

        private Error(int code, Error parent, Category category) {
            this(code, INHERIT, parent, category, null, null);
        }

        private Error(int code, Severity level, Error parent, Category category,
                      String rule, String explanation) {
            this.code = code;
            this.level = level;
            this.defaultLevel = level;
            this.parent = parent;
            this.category = category;
            this.rule = rule;
            this.explanation = explanation;
            errors.add(this);
        }

        /**
         * Returns the implied level for this error.
         * <p>
         * If the level is {@link Severity#INHERIT}, the level will be returned for the
         * parent.
         *
         * @throws IllegalStateException if the level is {@link Severity#INHERIT} and the
         *                               parent is {@code null}
         */
        public Severity getLevel() {
            if (level == INHERIT) {
                if (parent == null) {
                    throw new IllegalStateException("Error with level INHERIT must have non-null parent");
                }
                return parent.getLevel();
            }
            return level;
        }

        /**
         * Sets the level.
         * <p>
         * Valid arguments are:
         * <ul>
         * <li>{@link Severity#HIDDEN}
         * <li>{@link Severity#WARNING}
         * <li>{@link Severity#ERROR}
         * </ul>
         *
         * @param level the level to set
         */
        void setLevel(Severity level) {
            if (level == INHERIT) {
                throw new IllegalArgumentException("Error level may not be set to INHERIT");
            }
            this.level = level;
            this.setByUser = true;
        }

        public String toString() {
            return "Error #" + this.code + " (" + this.name + ")";
        }
    }

    private static final List<Error> errors = new ArrayList<>(100);
    private static final Map<String, Error> nameToError = new HashMap<>(100);
    private static final Map<Integer, Error> idToError = new HashMap<>(100);

    public enum Category {
        COMPATIBILITY("Compatibility", null),
        DOCUMENTATION("Documentation", null),
        API_LINT("API Lint", "go/android-api-guidelines"),
        UNKNOWN("Default", null);

        public final String description;
        public final String ruleLink;

        Category(String description, String ruleLink) {
            this.description = description;
            this.ruleLink = ruleLink;
        }
    }

    // Errors for API verification
    public static final Error PARSE_ERROR = new Error(1, ERROR);
    public static final Error ADDED_PACKAGE = new Error(2, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_CLASS = new Error(3, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_METHOD = new Error(4, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_FIELD = new Error(5, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_INTERFACE = new Error(6, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_PACKAGE = new Error(7, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_CLASS = new Error(8, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_METHOD = new Error(9, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_FIELD = new Error(10, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_INTERFACE = new Error(11, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_STATIC = new Error(12, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_FINAL = new Error(13, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_TRANSIENT = new Error(14, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_VOLATILE = new Error(15, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_TYPE = new Error(16, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_VALUE = new Error(17, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_SUPERCLASS = new Error(18, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_SCOPE = new Error(19, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_ABSTRACT = new Error(20, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_THROWS = new Error(21, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_NATIVE = new Error(22, HIDDEN, Category.COMPATIBILITY);
    public static final Error CHANGED_CLASS = new Error(23, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_DEPRECATED = new Error(24, WARNING, Category.COMPATIBILITY);
    public static final Error CHANGED_SYNCHRONIZED = new Error(25, WARNING, Category.COMPATIBILITY);
    public static final Error ADDED_FINAL_UNINSTANTIABLE = new Error(26, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_FINAL = new Error(27, WARNING, Category.COMPATIBILITY);
    public static final Error REMOVED_DEPRECATED_CLASS = new Error(28, REMOVED_CLASS, Category.COMPATIBILITY);
    public static final Error REMOVED_DEPRECATED_METHOD = new Error(29, REMOVED_METHOD, Category.COMPATIBILITY);
    public static final Error REMOVED_DEPRECATED_FIELD = new Error(30, REMOVED_FIELD, Category.COMPATIBILITY);
    public static final Error ADDED_ABSTRACT_METHOD = new Error(31, ADDED_METHOD, Category.COMPATIBILITY);
    public static final Error ADDED_REIFIED = new Error(32, WARNING, Category.COMPATIBILITY);

    // Errors in javadoc generation
    public static final Error UNRESOLVED_LINK = new Error(101, LINT, Category.DOCUMENTATION);
    public static final Error BAD_INCLUDE_TAG = new Error(102, LINT, Category.DOCUMENTATION);
    public static final Error UNKNOWN_TAG = new Error(103, LINT, Category.DOCUMENTATION);
    public static final Error UNKNOWN_PARAM_TAG_NAME = new Error(104, LINT, Category.DOCUMENTATION);
    public static final Error UNDOCUMENTED_PARAMETER = new Error(105, HIDDEN, Category.DOCUMENTATION);
    public static final Error BAD_ATTR_TAG = new Error(106, LINT, Category.DOCUMENTATION);
    public static final Error BAD_INHERITDOC = new Error(107, HIDDEN, Category.DOCUMENTATION);
    public static final Error HIDDEN_LINK = new Error(108, LINT, Category.DOCUMENTATION);
    public static final Error HIDDEN_CONSTRUCTOR = new Error(109, WARNING, Category.DOCUMENTATION);
    public static final Error UNAVAILABLE_SYMBOL = new Error(110, WARNING, Category.DOCUMENTATION);
    public static final Error HIDDEN_SUPERCLASS = new Error(111, WARNING, Category.DOCUMENTATION);
    public static final Error DEPRECATED = new Error(112, HIDDEN, Category.DOCUMENTATION);
    public static final Error DEPRECATION_MISMATCH = new Error(113, ERROR, Category.DOCUMENTATION);
    public static final Error MISSING_COMMENT = new Error(114, LINT, Category.DOCUMENTATION);
    public static final Error IO_ERROR = new Error(115, ERROR);
    public static final Error NO_SINCE_DATA = new Error(116, HIDDEN, Category.DOCUMENTATION);
    public static final Error NO_FEDERATION_DATA = new Error(117, WARNING, Category.DOCUMENTATION);
    public static final Error BROKEN_SINCE_FILE = new Error(118, ERROR, Category.DOCUMENTATION);
    public static final Error INVALID_CONTENT_TYPE = new Error(119, ERROR, Category.DOCUMENTATION);
    public static final Error INVALID_SAMPLE_INDEX = new Error(120, ERROR, Category.DOCUMENTATION);
    public static final Error HIDDEN_TYPE_PARAMETER = new Error(121, WARNING, Category.DOCUMENTATION);
    public static final Error PRIVATE_SUPERCLASS = new Error(122, WARNING, Category.DOCUMENTATION);
    public static final Error NULLABLE = new Error(123, HIDDEN, Category.DOCUMENTATION);
    public static final Error INT_DEF = new Error(124, HIDDEN, Category.DOCUMENTATION);
    public static final Error REQUIRES_PERMISSION = new Error(125, LINT, Category.DOCUMENTATION);
    public static final Error BROADCAST_BEHAVIOR = new Error(126, LINT, Category.DOCUMENTATION);
    public static final Error SDK_CONSTANT = new Error(127, LINT, Category.DOCUMENTATION);
    public static final Error TODO = new Error(128, LINT, Category.DOCUMENTATION);
    public static final Error NO_ARTIFACT_DATA = new Error(129, HIDDEN, Category.DOCUMENTATION);
    public static final Error BROKEN_ARTIFACT_FILE = new Error(130, ERROR, Category.DOCUMENTATION);

    // Metalava new warnings (not from doclava)

    public static final Error TYPO = new Error(131, WARNING, Category.DOCUMENTATION);
    public static final Error MISSING_PERMISSION = new Error(132, LINT, Category.DOCUMENTATION);
    public static final Error MULTIPLE_THREAD_ANNOTATIONS = new Error(133, LINT, Category.DOCUMENTATION);
    public static final Error UNRESOLVED_CLASS = new Error(134, LINT, Category.DOCUMENTATION);
    public static final Error INVALID_NULL_CONVERSION = new Error(135, ERROR, Category.COMPATIBILITY);
    public static final Error PARAMETER_NAME_CHANGE = new Error(136, ERROR, Category.COMPATIBILITY);
    public static final Error OPERATOR_REMOVAL = new Error(137, ERROR, Category.COMPATIBILITY);
    public static final Error INFIX_REMOVAL = new Error(138, ERROR, Category.COMPATIBILITY);
    public static final Error VARARG_REMOVAL = new Error(139, ERROR, Category.COMPATIBILITY);
    public static final Error ADD_SEALED = new Error(140, ERROR, Category.COMPATIBILITY);
    public static final Error ANNOTATION_EXTRACTION = new Error(146, ERROR);
    public static final Error SUPERFLUOUS_PREFIX = new Error(147, WARNING);
    public static final Error HIDDEN_TYPEDEF_CONSTANT = new Error(148, ERROR);
    public static final Error EXPECTED_PLATFORM_TYPE = new Error(149, HIDDEN);
    public static final Error INTERNAL_ERROR = new Error(150, ERROR);
    public static final Error RETURNING_UNEXPECTED_CONSTANT = new Error(151, WARNING);
    public static final Error DEPRECATED_OPTION = new Error(152, WARNING);
    public static final Error BOTH_PACKAGE_INFO_AND_HTML = new Error(153, WARNING, Category.DOCUMENTATION);
    // The plan is for this to be set as an error once (1) existing code is marked as @deprecated
    // and (2) the principle is adopted by the API council
    public static final Error REFERENCES_DEPRECATED = new Error(154, HIDDEN);
    public static final Error UNHIDDEN_SYSTEM_API = new Error(155, ERROR);
    public static final Error SHOWING_MEMBER_IN_HIDDEN_CLASS = new Error(156, ERROR);
    public static final Error INVALID_NULLABILITY_ANNOTATION = new Error(157, ERROR);
    public static final Error REFERENCES_HIDDEN = new Error(158, ERROR);
    public static final Error IGNORING_SYMLINK = new Error(159, INFO);
    public static final Error INVALID_NULLABILITY_ANNOTATION_WARNING = new Error(160, WARNING);
    // The plan is for this to be set as an error once (1) existing code is marked as @deprecated
    // and (2) the principle is adopted by the API council
    public static final Error EXTENDS_DEPRECATED = new Error(161, HIDDEN);
    public static final Error FORBIDDEN_TAG = new Error(162, ERROR);

    // API lint
    public static final Error START_WITH_LOWER = new Error(300, ERROR, Category.API_LINT, "S1");
    public static final Error START_WITH_UPPER = new Error(301, ERROR, Category.API_LINT, "S1");
    public static final Error ALL_UPPER = new Error(302, ERROR, Category.API_LINT, "C2");
    public static final Error ACRONYM_NAME = new Error(303, WARNING, Category.API_LINT, "S1");
    public static final Error ENUM = new Error(304, ERROR, Category.API_LINT, "F5");
    public static final Error ENDS_WITH_IMPL = new Error(305, ERROR, Category.API_LINT);
    public static final Error MIN_MAX_CONSTANT = new Error(306, WARNING, Category.API_LINT, "C8");
    public static final Error COMPILE_TIME_CONSTANT = new Error(307, ERROR, Category.API_LINT);
    public static final Error SINGULAR_CALLBACK = new Error(308, ERROR, Category.API_LINT, "L1");
    public static final Error CALLBACK_NAME = new Error(309, WARNING, Category.API_LINT, "L1");
    public static final Error CALLBACK_INTERFACE = new Error(310, ERROR, Category.API_LINT, "CL3");
    public static final Error CALLBACK_METHOD_NAME = new Error(311, ERROR, Category.API_LINT, "L1");
    public static final Error LISTENER_INTERFACE = new Error(312, ERROR, Category.API_LINT, "L1");
    public static final Error SINGLE_METHOD_INTERFACE = new Error(313, ERROR, Category.API_LINT, "L1");
    public static final Error INTENT_NAME = new Error(314, ERROR, Category.API_LINT, "C3");
    public static final Error ACTION_VALUE = new Error(315, ERROR, Category.API_LINT, "C4");
    public static final Error EQUALS_AND_HASH_CODE = new Error(316, ERROR, Category.API_LINT, "M8");
    public static final Error PARCEL_CREATOR = new Error(317, ERROR, Category.API_LINT, "FW3");
    public static final Error PARCEL_NOT_FINAL = new Error(318, ERROR, Category.API_LINT, "FW8");
    public static final Error PARCEL_CONSTRUCTOR = new Error(319, ERROR, Category.API_LINT, "FW3");
    public static final Error PROTECTED_MEMBER = new Error(320, ERROR, Category.API_LINT, "M7");
    public static final Error PAIRED_REGISTRATION = new Error(321, ERROR, Category.API_LINT, "L2");
    public static final Error REGISTRATION_NAME = new Error(322, ERROR, Category.API_LINT, "L3");
    public static final Error VISIBLY_SYNCHRONIZED = new Error(323, ERROR, Category.API_LINT, "M5");
    public static final Error INTENT_BUILDER_NAME = new Error(324, WARNING, Category.API_LINT, "FW1");
    public static final Error CONTEXT_NAME_SUFFIX = new Error(325, ERROR, Category.API_LINT, "C4");
    public static final Error INTERFACE_CONSTANT = new Error(326, ERROR, Category.API_LINT, "C4");
    public static final Error ON_NAME_EXPECTED = new Error(327, WARNING, Category.API_LINT);
    public static final Error TOP_LEVEL_BUILDER = new Error(328, WARNING, Category.API_LINT);
    public static final Error MISSING_BUILD = new Error(329, WARNING, Category.API_LINT);
    public static final Error BUILDER_SET_STYLE = new Error(330, WARNING, Category.API_LINT);
    public static final Error SETTER_RETURNS_THIS = new Error(331, WARNING, Category.API_LINT, "M4");
    public static final Error RAW_AIDL = new Error(332, ERROR, Category.API_LINT);
    public static final Error INTERNAL_CLASSES = new Error(333, ERROR, Category.API_LINT);
    public static final Error PACKAGE_LAYERING = new Error(334, WARNING, Category.API_LINT, "FW6");
    public static final Error GETTER_SETTER_NAMES = new Error(335, ERROR, Category.API_LINT, "M6");
    public static final Error CONCRETE_COLLECTION = new Error(336, ERROR, Category.API_LINT, "CL2");
    public static final Error OVERLAPPING_CONSTANTS = new Error(337, WARNING, Category.API_LINT, "C1");
    public static final Error GENERIC_EXCEPTION = new Error(338, ERROR, Category.API_LINT, "S1");
    public static final Error ILLEGAL_STATE_EXCEPTION = new Error(339, WARNING, Category.API_LINT, "S1");
    public static final Error RETHROW_REMOTE_EXCEPTION = new Error(340, ERROR, Category.API_LINT, "FW9");
    public static final Error MENTIONS_GOOGLE = new Error(341, ERROR, Category.API_LINT);
    public static final Error HEAVY_BIT_SET = new Error(342, ERROR, Category.API_LINT);
    public static final Error MANAGER_CONSTRUCTOR = new Error(343, ERROR, Category.API_LINT);
    public static final Error MANAGER_LOOKUP = new Error(344, ERROR, Category.API_LINT);
    public static final Error AUTO_BOXING = new Error(345, ERROR, Category.API_LINT, "M11");
    public static final Error STATIC_UTILS = new Error(346, ERROR, Category.API_LINT);
    public static final Error CONTEXT_FIRST = new Error(347, ERROR, Category.API_LINT, "M3");
    public static final Error LISTENER_LAST = new Error(348, WARNING, Category.API_LINT, "M3");
    public static final Error EXECUTOR_REGISTRATION = new Error(349, WARNING, Category.API_LINT, "L1");
    public static final Error CONFIG_FIELD_NAME = new Error(350, ERROR, Category.API_LINT);
    public static final Error RESOURCE_FIELD_NAME = new Error(351, ERROR, Category.API_LINT);
    public static final Error RESOURCE_VALUE_FIELD_NAME = new Error(352, ERROR, Category.API_LINT, "C7");
    public static final Error RESOURCE_STYLE_FIELD_NAME = new Error(353, ERROR, Category.API_LINT, "C7");
    public static final Error STREAM_FILES = new Error(354, WARNING, Category.API_LINT, "M10");
    public static final Error PARCELABLE_LIST = new Error(355, WARNING, Category.API_LINT);
    public static final Error ABSTRACT_INNER = new Error(356, WARNING, Category.API_LINT);
    public static final Error BANNED_THROW = new Error(358, ERROR, Category.API_LINT);
    public static final Error EXTENDS_ERROR = new Error(359, ERROR, Category.API_LINT);
    public static final Error EXCEPTION_NAME = new Error(360, ERROR, Category.API_LINT);
    public static final Error METHOD_NAME_UNITS = new Error(361, ERROR, Category.API_LINT);
    public static final Error FRACTION_FLOAT = new Error(362, ERROR, Category.API_LINT);
    public static final Error PERCENTAGE_INT = new Error(363, ERROR, Category.API_LINT);
    public static final Error NOT_CLOSEABLE = new Error(364, WARNING, Category.API_LINT);
    public static final Error KOTLIN_OPERATOR = new Error(365, INFO, Category.API_LINT);
    public static final Error ARRAY_RETURN = new Error(366, WARNING, Category.API_LINT);
    public static final Error USER_HANDLE = new Error(367, WARNING, Category.API_LINT);
    public static final Error USER_HANDLE_NAME = new Error(368, WARNING, Category.API_LINT);
    public static final Error SERVICE_NAME = new Error(369, ERROR, Category.API_LINT, "C4");
    public static final Error METHOD_NAME_TENSE = new Error(370, WARNING, Category.API_LINT);
    public static final Error NO_CLONE = new Error(371, ERROR, Category.API_LINT);
    public static final Error USE_ICU = new Error(372, WARNING, Category.API_LINT);
    public static final Error USE_PARCEL_FILE_DESCRIPTOR = new Error(373, ERROR, Category.API_LINT, "FW11");
    public static final Error NO_BYTE_OR_SHORT = new Error(374, WARNING, Category.API_LINT, "FW12");
    public static final Error SINGLETON_CONSTRUCTOR = new Error(375, ERROR, Category.API_LINT);
    public static final Error COMMON_ARGS_FIRST = new Error(376, WARNING, Category.API_LINT, "M2");
    public static final Error CONSISTENT_ARGUMENT_ORDER = new Error(377, ERROR, Category.API_LINT, "M2");
    public static final Error KOTLIN_KEYWORD = new Error(378, ERROR, Category.API_LINT); // Formerly 141
    public static final Error UNIQUE_KOTLIN_OPERATOR = new Error(379, ERROR, Category.API_LINT);
    public static final Error SAM_SHOULD_BE_LAST = new Error(380, WARNING, Category.API_LINT); // Formerly 142
    public static final Error MISSING_JVMSTATIC = new Error(381, WARNING, Category.API_LINT); // Formerly 143
    public static final Error DEFAULT_VALUE_CHANGE = new Error(382, ERROR, Category.API_LINT); // Formerly 144
    public static final Error DOCUMENT_EXCEPTIONS = new Error(383, ERROR, Category.API_LINT); // Formerly 145
    public static final Error FORBIDDEN_SUPER_CLASS = new Error(384, ERROR, Category.API_LINT);

    static {
        // Attempt to initialize error names based on the field names
        try {
            for (Field field : Errors.class.getDeclaredFields()) {
                Object o = field.get(null);
                if (o instanceof Error) {
                    Error error = (Error) o;
                    String fieldName = field.getName();
                    error.fieldName = fieldName;
                    error.name = underlinesToCamelCase(fieldName.toLowerCase(Locale.US));
                    nameToError.put(error.name, error);
                    idToError.put(error.code, error);
                }
            }
        } catch (Throwable unexpected) {
            unexpected.printStackTrace();
        }
    }

    @Nullable
    public static Error findErrorById(int id) {
        return idToError.get(id);
    }

    @Nullable
    public static Error findErrorById(String id) {
        return nameToError.get(id);
    }

    public static boolean setErrorLevel(String id, Severity level, boolean setByUser) {
        if (id.contains(",")) { // Handle being passed in multiple comma separated id's
            boolean ok = true;
            for (String individualId : Splitter.on(',').trimResults().split(id)) {
                ok = setErrorLevel(individualId, level, setByUser) && ok;
            }
            return ok;
        }
        int code = -1;
        if (Character.isDigit(id.charAt(0))) {
            code = Integer.parseInt(id);
        }

        Error error = nameToError.get(id);
        if (error == null) {
            try {
                int n = Integer.parseInt(id);
                error = idToError.get(n);
            } catch (NumberFormatException ignore) {
            }
        }

        if (error == null) {
            for (Error e : errors) {
                if (e.code == code || id.equalsIgnoreCase(e.name)) {
                    error = e;
                    break;
                }
            }
        }

        if (error != null) {
            error.setLevel(level);
            error.setByUser = setByUser;
            return true;
        }
        return false;
    }

    // Primary needed by unit tests; ensure that a previous test doesn't influence
    // a later one
    public static void resetLevels() {
        for (Error error : errors) {
            error.level = error.defaultLevel;
        }
    }
}
