/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.bedstead.harrier;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bedstead.harrier.annotations.AnnotationRunPrecedence;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.EnterprisePolicy;
import com.android.bedstead.harrier.annotations.enterprise.NegativePolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PositivePolicyTest;
import com.android.bedstead.harrier.annotations.meta.ParameterizedAnnotation;
import com.android.bedstead.harrier.annotations.meta.RepeatingAnnotation;
import com.android.bedstead.harrier.annotations.parameterized.IncludeNone;
import com.android.bedstead.nene.exceptions.NeneException;

import com.google.common.base.Objects;

import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A JUnit test runner for use with Bedstead.
 */
public final class BedsteadJUnit4 extends BlockJUnit4ClassRunner {

    private static final String BEDSTEAD_PACKAGE_NAME = "com.android.bedstead";

    // These are annotations which are not included indirectly
    private static final Set<String> sIgnoredAnnotationPackages = new HashSet<>();
    static {
        sIgnoredAnnotationPackages.add("java.lang.annotation");
        sIgnoredAnnotationPackages.add("com.android.bedstead.harrier.annotations.meta");
        sIgnoredAnnotationPackages.add("kotlin.*");
        sIgnoredAnnotationPackages.add("org.junit");
    }

    private static int annotationSorter(Annotation a, Annotation b) {
        return getAnnotationWeight(a) - getAnnotationWeight(b);
    }

    private static int getAnnotationWeight(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            // Special case, not important
            return AnnotationRunPrecedence.PRECEDENCE_NOT_IMPORTANT;
        }

        if (!annotation.annotationType().getPackage().getName().startsWith(BEDSTEAD_PACKAGE_NAME)) {
            return AnnotationRunPrecedence.FIRST;
        }

        try {
            return (int) annotation.annotationType().getMethod("weight").invoke(annotation);
        } catch (NoSuchMethodException e) {
            // Default to PRECEDENCE_NOT_IMPORTANT if no weight is found on the annotation.
            return AnnotationRunPrecedence.PRECEDENCE_NOT_IMPORTANT;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new NeneException("Failed to invoke weight on this annotation: " + annotation, e);
        }
    }

    /**
     * {@link FrameworkMethod} subclass which allows modifying the test name and annotations.
     */
    public static final class BedsteadFrameworkMethod extends FrameworkMethod {

        private final Annotation mParameterizedAnnotation;
        private final Map<Class<? extends Annotation>, Annotation> mAnnotationsMap =
                new HashMap<>();
        private Annotation[] mAnnotations;

        public BedsteadFrameworkMethod(Method method) {
            this(method, /* parameterizedAnnotation= */ null);
        }

        public BedsteadFrameworkMethod(Method method, Annotation parameterizedAnnotation) {
            super(method);
            mParameterizedAnnotation = parameterizedAnnotation;

            calculateAnnotations();
        }

        private void calculateAnnotations() {
            List<Annotation> annotations =
                    new ArrayList<>(Arrays.asList(getDeclaringClass().getAnnotations()));
            annotations.sort(BedsteadJUnit4::annotationSorter);

            annotations.addAll(Arrays.stream(getMethod().getAnnotations())
                    .sorted(BedsteadJUnit4::annotationSorter)
                    .collect(Collectors.toList()));

            parseEnterpriseAnnotations(annotations);

            resolveRecursiveAnnotations(annotations, mParameterizedAnnotation);

            this.mAnnotations = annotations.toArray(new Annotation[0]);
            for (Annotation annotation : annotations) {
                if (annotation instanceof DynamicParameterizedAnnotation) {
                    continue; // don't return this
                }
                mAnnotationsMap.put(annotation.annotationType(), annotation);
            }
        }

        @Override
        public String getName() {
            if (mParameterizedAnnotation == null) {
                return super.getName();
            }
            return super.getName() + "[" + getParameterName(mParameterizedAnnotation) + "]";
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }

            if (!(obj instanceof BedsteadFrameworkMethod)) {
                return false;
            }

            BedsteadFrameworkMethod other = (BedsteadFrameworkMethod) obj;

            return Objects.equal(mParameterizedAnnotation, other.mParameterizedAnnotation);
        }

        @Override
        public Annotation[] getAnnotations() {
            return mAnnotations;
        }

        @Override
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            return (T) mAnnotationsMap.get(annotationType);
        }
    }

    private static String getParameterName(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return ((DynamicParameterizedAnnotation) annotation).name();
        }
        return annotation.annotationType().getSimpleName();
    }

    /**
     * Resolve annotations recursively.
     *
     * @param parameterizedAnnotation The class of the parameterized annotation to expand, if any
     */
    public static void resolveRecursiveAnnotations(List<Annotation> annotations,
            @Nullable Annotation parameterizedAnnotation) {
        int index = 0;
        while (index < annotations.size()) {
            Annotation annotation = annotations.get(index);
            annotations.remove(index);
            List<Annotation> replacementAnnotations =
                    getReplacementAnnotations(annotation, parameterizedAnnotation);
            replacementAnnotations.sort(BedsteadJUnit4::annotationSorter);
            annotations.addAll(index, replacementAnnotations);
            index += replacementAnnotations.size();
        }
    }

    private static boolean isParameterizedAnnotation(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return true;
        }

        return annotation.annotationType().getAnnotation(ParameterizedAnnotation.class) != null;
    }

    private static Annotation[] getIndirectAnnotations(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return ((DynamicParameterizedAnnotation) annotation).annotations();
        }
        return annotation.annotationType().getAnnotations();
    }

    private static boolean isRepeatingAnnotation(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return false;
        }

        return annotation.annotationType().getAnnotation(RepeatingAnnotation.class) != null;
    }

    private static List<Annotation> getReplacementAnnotations(Annotation annotation,
            @Nullable Annotation parameterizedAnnotation) {
        List<Annotation> replacementAnnotations = new ArrayList<>();

        if (isRepeatingAnnotation(annotation)) {
            try {
                Annotation[] annotations =
                        (Annotation[]) annotation.annotationType()
                                .getMethod("value").invoke(annotation);
                Collections.addAll(replacementAnnotations, annotations);
                return replacementAnnotations;
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new NeneException("Error expanding repeated annotations", e);
            }
        }

        if (isParameterizedAnnotation(annotation) && !annotation.equals(parameterizedAnnotation)) {
            return replacementAnnotations;
        }

        for (Annotation indirectAnnotation : getIndirectAnnotations(annotation)) {
            if (shouldSkipAnnotation(annotation)) {
                continue;
            }

            replacementAnnotations.addAll(getReplacementAnnotations(
                    indirectAnnotation, parameterizedAnnotation));
        }

        if (!(annotation instanceof DynamicParameterizedAnnotation)) {
            // We drop the fake annotation once it's replaced
            replacementAnnotations.add(annotation);
        }

        return replacementAnnotations;
    }

    private static boolean shouldSkipAnnotation(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return false;
        }

        String annotationPackage = annotation.annotationType().getPackage().getName();

        for (String ignoredPackage : sIgnoredAnnotationPackages) {
            if (ignoredPackage.endsWith(".*")) {
                if (annotationPackage.startsWith(
                    ignoredPackage.substring(0, ignoredPackage.length() - 2))) {
                    return true;
                }
            } else if (annotationPackage.equals(ignoredPackage)) {
                return true;
            }
        }

        return false;
    }

    public BedsteadJUnit4(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    private boolean annotationShouldBeSkipped(Annotation annotation) {
        if (annotation instanceof DynamicParameterizedAnnotation) {
            return false;
        }

        return annotation.annotationType().equals(IncludeNone.class);
    }

    @Override
    protected List<FrameworkMethod> computeTestMethods() {
        TestClass testClass = getTestClass();

        List<FrameworkMethod> basicTests = testClass.getAnnotatedMethods(Test.class);
        List<FrameworkMethod> modifiedTests = new ArrayList<>();

        for (FrameworkMethod m : basicTests) {
            Set<Annotation> parameterizedAnnotations = getParameterizedAnnotations(m);

            if (parameterizedAnnotations.isEmpty()) {
                // Unparameterized, just add the original
                modifiedTests.add(new BedsteadFrameworkMethod(m.getMethod()));
            }

            for (Annotation annotation : parameterizedAnnotations) {
                if (annotationShouldBeSkipped(annotation)) {
                    // Special case - does not generate a run
                    continue;
                }
                modifiedTests.add(
                        new BedsteadFrameworkMethod(m.getMethod(), annotation));
            }
        }

        sortMethodsByBedsteadAnnotations(modifiedTests);

        return modifiedTests;
    }

    /**
     * Sort methods so that methods with identical bedstead annotations are together.
     *
     * <p>This will also ensure that all tests methods which are not annotated for bedstead will
     * run before any tests which are annotated.
     */
    private void sortMethodsByBedsteadAnnotations(List<FrameworkMethod> modifiedTests) {
        List<Annotation> bedsteadAnnotationsSortedByMostCommon =
                bedsteadAnnotationsSortedByMostCommon(modifiedTests);

        modifiedTests.sort((o1, o2) -> {
            for (Annotation annotation : bedsteadAnnotationsSortedByMostCommon) {
                boolean o1HasAnnotation = o1.getAnnotation(annotation.annotationType()) != null;
                boolean o2HasAnnotation = o2.getAnnotation(annotation.annotationType()) != null;

                if (o1HasAnnotation && !o2HasAnnotation) {
                    // o1 goes to the end
                    return 1;
                } else if (o2HasAnnotation && !o1HasAnnotation) {
                    return -1;
                }
            }
            return 0;
        });
    }

    private List<Annotation> bedsteadAnnotationsSortedByMostCommon(List<FrameworkMethod> methods) {
        Map<Annotation, Integer> annotationCounts = countAnnotations(methods);
        List<Annotation> annotations = new ArrayList<>(annotationCounts.keySet());

        annotations.removeIf(
                annotation ->
                        !annotation.annotationType()
                                .getCanonicalName().contains(BEDSTEAD_PACKAGE_NAME));

        annotations.sort(Comparator.comparingInt(annotationCounts::get));
        Collections.reverse(annotations);

        return annotations;
    }

    private Map<Annotation, Integer> countAnnotations(List<FrameworkMethod> methods) {
        Map<Annotation, Integer> annotationCounts = new HashMap<>();

        for (FrameworkMethod method : methods) {
            for (Annotation annotation : method.getAnnotations()) {
                annotationCounts.put(
                        annotation, annotationCounts.getOrDefault(annotation, 0) + 1);
            }
        }

        return annotationCounts;
    }

    private Set<Annotation> getParameterizedAnnotations(FrameworkMethod method) {
        Set<Annotation> parameterizedAnnotations = new HashSet<>();
        List<Annotation> annotations = new ArrayList<>(Arrays.asList(method.getAnnotations()));

        // TODO(scottjonathan): We're doing this twice... does it matter?
        parseEnterpriseAnnotations(annotations);

        for (Annotation annotation : annotations) {
            if (isParameterizedAnnotation(annotation)) {
                parameterizedAnnotations.add(annotation);
            }
        }

        return parameterizedAnnotations;
    }

    /**
     * Parse enterprise-specific annotations.
     *
     * <p>To be used before general annotation processing.
     */
    private static void parseEnterpriseAnnotations(List<Annotation> annotations) {
        int index = 0;
        while (index < annotations.size()) {
            Annotation annotation = annotations.get(index);
            if (annotation instanceof PositivePolicyTest) {
                annotations.remove(index);
                Class<?> policy = ((PositivePolicyTest) annotation).policy();

                EnterprisePolicy enterprisePolicy =
                        policy.getAnnotation(EnterprisePolicy.class);
                List<Annotation> replacementAnnotations =
                        Policy.positiveStates(policy.getName(), enterprisePolicy);
                replacementAnnotations.sort(BedsteadJUnit4::annotationSorter);

                annotations.addAll(index, replacementAnnotations);
                index += replacementAnnotations.size();
            } else if (annotation instanceof NegativePolicyTest) {
                annotations.remove(index);
                Class<?> policy = ((NegativePolicyTest) annotation).policy();

                EnterprisePolicy enterprisePolicy =
                        policy.getAnnotation(EnterprisePolicy.class);
                List<Annotation> replacementAnnotations =
                        Policy.negativeStates(policy.getName(), enterprisePolicy);
                replacementAnnotations.sort(BedsteadJUnit4::annotationSorter);

                annotations.addAll(index, replacementAnnotations);
                index += replacementAnnotations.size();
            } else if (annotation instanceof CannotSetPolicyTest) {
                annotations.remove(index);
                Class<?> policy = ((CannotSetPolicyTest) annotation).policy();

                EnterprisePolicy enterprisePolicy =
                        policy.getAnnotation(EnterprisePolicy.class);
                List<Annotation> replacementAnnotations =
                        Policy.cannotSetPolicyStates(policy.getName(), enterprisePolicy, ((CannotSetPolicyTest) annotation).includeDeviceAdminStates(), ((CannotSetPolicyTest) annotation).includeNonDeviceAdminStates());
                replacementAnnotations.sort(BedsteadJUnit4::annotationSorter);

                annotations.addAll(index, replacementAnnotations);
                index += replacementAnnotations.size();
            } else if (annotation instanceof CanSetPolicyTest) {
                annotations.remove(index);
                Class<?> policy = ((CanSetPolicyTest) annotation).policy();
                boolean singleTestOnly = ((CanSetPolicyTest) annotation).singleTestOnly();

                EnterprisePolicy enterprisePolicy =
                        policy.getAnnotation(EnterprisePolicy.class);
                List<Annotation> replacementAnnotations =
                        Policy.canSetPolicyStates(
                                policy.getName(), enterprisePolicy, singleTestOnly);
                replacementAnnotations.sort(BedsteadJUnit4::annotationSorter);

                annotations.addAll(index, replacementAnnotations);
                index += replacementAnnotations.size();
            } else {
                index++;
            }
        }
    }

    @Override
    protected List<TestRule> classRules() {
        List<TestRule> rules = super.classRules();

        for (TestRule rule : rules) {
            if (rule instanceof DeviceState) {
                DeviceState deviceState = (DeviceState) rule;

                deviceState.setSkipTestTeardown(true);
                deviceState.setUsingBedsteadJUnit4(true);

                break;
            }
        }

        return rules;
    }

    /**
     * True if the test is running in debug mode.
     *
     * <p>This will result in additional debugging information being added which would otherwise
     * be dropped to improve test performance.
     *
     * <p>To enable this, pass the "bedstead-debug" instrumentation arg as "true"
     */
    public static boolean isDebug() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        return Boolean.parseBoolean(arguments.getString("bedstead-debug", "false"));
    }
}
