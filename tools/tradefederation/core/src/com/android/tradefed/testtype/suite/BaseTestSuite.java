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
package com.android.tradefed.testtype.suite;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.suite.params.ModuleParameters;
import com.android.tradefed.util.ArrayUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A Test for running Compatibility Test Suite with new suite system. */
@OptionClass(alias = "base-suite")
public class BaseTestSuite extends ITestSuite {

    public static final String INCLUDE_FILTER_OPTION = "include-filter";
    public static final String EXCLUDE_FILTER_OPTION = "exclude-filter";
    public static final String MODULE_OPTION = "module";
    public static final String TEST_ARG_OPTION = "test-arg";
    public static final String TEST_OPTION = "test";
    public static final char TEST_OPTION_SHORT_NAME = 't';
    public static final String CONFIG_PATTERNS_OPTION = "config-patterns";
    private static final String MODULE_ARG_OPTION = "module-arg";

    @Option(
        name = INCLUDE_FILTER_OPTION,
        description = "the include module filters to apply.",
        importance = Importance.ALWAYS
    )
    private Set<String> mIncludeFilters = new HashSet<>();

    @Option(
        name = EXCLUDE_FILTER_OPTION,
        description = "the exclude module filters to apply.",
        importance = Importance.ALWAYS
    )
    private Set<String> mExcludeFilters = new HashSet<>();

    @Option(
        name = MODULE_OPTION,
        shortName = 'm',
        description = "the test module to run. Only works for configuration in the tests dir.",
        importance = Importance.IF_UNSET
    )
    private String mModuleName = null;

    @Option(
        name = TEST_OPTION,
        shortName = TEST_OPTION_SHORT_NAME,
        description = "the test to run.",
        importance = Importance.IF_UNSET
    )
    private String mTestName = null;

    @Option(
        name = MODULE_ARG_OPTION,
        description =
                "the arguments to pass to a module. The expected format is"
                        + "\"<module-name>:[{alias}]<arg-name>:[<arg-key>:=]<arg-value>\"",
        importance = Importance.ALWAYS
    )
    private List<String> mModuleArgs = new ArrayList<>();

    @Option(
        name = TEST_ARG_OPTION,
        description =
                "the arguments to pass to a test. The expected format is"
                        + "\"<test-class>:<arg-name>:[<arg-key>:=]<arg-value>\"",
        importance = Importance.ALWAYS
    )
    private List<String> mTestArgs = new ArrayList<>();

    @Option(
        name = "run-suite-tag",
        description =
                "The tag that must be run. If specified, only configurations containing the "
                        + "matching suite tag will be able to run."
    )
    private String mSuiteTag = null;

    @Option(
        name = "prioritize-host-config",
        description =
                "If there are duplicate test configs for host/target, prioritize the host config, "
                        + "otherwise use the target config."
    )
    private boolean mPrioritizeHostConfig = false;

    @Option(
        name = "suite-config-prefix",
        description = "Search only configs with given prefix for suite tags."
    )
    private String mSuitePrefix = null;

    @Option(
        name = "skip-loading-config-jar",
        description = "Whether or not to skip loading configurations from the JAR on the classpath."
    )
    private boolean mSkipJarLoading = false;

    @Option(
        name = CONFIG_PATTERNS_OPTION,
        description =
                "The pattern(s) of the configurations that should be loaded from a directory."
                        + " If none is explicitly specified, .*.xml and .*.config will be used."
                        + " Can be repeated."
    )
    private List<String> mConfigPatterns = new ArrayList<>();

    @Option(
        name = "enable-parameterized-modules",
        description =
                "Whether or not to enable parameterized modules. This is a feature flag for work "
                        + "in development."
    )
    private boolean mEnableParameter = false;

    @Option(
        name = "module-parameter",
        description =
                "Allows to run only one module parameter type instead of all the combinations. "
                        + "For example: 'instant_app' would only run the instant_app version of "
                        + "modules"
    )
    private ModuleParameters mForceParameter = null;

    @Option(
        name = "exclude-module-parameters",
        description =
                "Exclude some modules parameter from being evaluated in the run combinations."
                        + "For example: 'instant_app' would exclude all the instant_app version of "
                        + "modules."
    )
    private Set<ModuleParameters> mExcludedModuleParameters = new HashSet<>();

    private SuiteModuleLoader mModuleRepo;
    private Map<String, List<SuiteTestFilter>> mIncludeFiltersParsed = new HashMap<>();
    private Map<String, List<SuiteTestFilter>> mExcludeFiltersParsed = new HashMap<>();

    /** {@inheritDoc} */
    @Override
    public LinkedHashMap<String, IConfiguration> loadTests() {
        try {
            File testsDir = getTestsDir();
            setupFilters(testsDir);
            Set<IAbi> abis = getAbis(getDevice());

            // Create and populate the filters here
            SuiteModuleLoader.addFilters(mIncludeFilters, mIncludeFiltersParsed, abis);
            SuiteModuleLoader.addFilters(mExcludeFilters, mExcludeFiltersParsed, abis);

            CLog.d(
                    "Initializing ModuleRepo\nABIs:%s\n"
                            + "Test Args:%s\nModule Args:%s\nIncludes:%s\nExcludes:%s",
                    abis, mTestArgs, mModuleArgs, mIncludeFiltersParsed, mExcludeFiltersParsed);
            mModuleRepo =
                    createModuleLoader(
                            mIncludeFiltersParsed, mExcludeFiltersParsed, mTestArgs, mModuleArgs);
            mModuleRepo.setParameterizedModules(mEnableParameter);
            mModuleRepo.setModuleParameter(mForceParameter);
            mModuleRepo.setExcludedModuleParameters(mExcludedModuleParameters);

            List<File> testsDirectories = new ArrayList<>();

            // Include host or target first in the search if it exists, we have to this in
            // BaseTestSuite because it's the only one with the BuildInfo knowledge of linked files
            if (mPrioritizeHostConfig) {
                File hostSubDir = getBuildInfo().getFile(BuildInfoFileKey.HOST_LINKED_DIR);
                if (hostSubDir != null && hostSubDir.exists()) {
                    testsDirectories.add(hostSubDir);
                }
            } else {
                File targetSubDir = getBuildInfo().getFile(BuildInfoFileKey.TARGET_LINKED_DIR);
                if (targetSubDir != null && targetSubDir.exists()) {
                    testsDirectories.add(targetSubDir);
                }
            }

            // Finally add the full test cases directory in case there is no special sub-dir.
            testsDirectories.add(testsDir);
            // Actual loading of the configurations.
            return loadingStrategy(abis, testsDirectories, mSuitePrefix, mSuiteTag);
        } catch (DeviceNotAvailableException | FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Default loading strategy will load from the resources and the tests directory. Can be
     * extended or replaced.
     *
     * @param abis The set of abis to run against.
     * @param testsDirs The tests directory.
     * @param suitePrefix A prefix to filter the resource directory.
     * @param suiteTag The suite tag a module should have to be included. Can be null.
     * @return A list of loaded configuration for the suite.
     */
    public LinkedHashMap<String, IConfiguration> loadingStrategy(
            Set<IAbi> abis, List<File> testsDirs, String suitePrefix, String suiteTag) {
        LinkedHashMap<String, IConfiguration> loadedConfigs = new LinkedHashMap<>();
        // Load configs that are part of the resources
        if (!mSkipJarLoading) {
            loadedConfigs.putAll(
                    getModuleLoader().loadConfigsFromJars(abis, suitePrefix, suiteTag));
        }

        // Load the configs that are part of the tests dir
        if (mConfigPatterns.isEmpty()) {
            // If no special pattern was configured, use the default configuration patterns we know
            mConfigPatterns.add(".*\\.config$");
            mConfigPatterns.add(".*\\.xml$");
        }

        loadedConfigs.putAll(
                getModuleLoader()
                        .loadConfigsFromDirectory(
                                testsDirs, abis, suitePrefix, suiteTag, mConfigPatterns));
        return loadedConfigs;
    }

    public File getTestsDir() throws FileNotFoundException {
        IBuildInfo build = getBuildInfo();
        if (build instanceof IDeviceBuildInfo) {
            return ((IDeviceBuildInfo) build).getTestsDir();
        }
        // TODO: handle multi build?
        throw new FileNotFoundException("Could not found a tests dir folder.");
    }

    /** {@inheritDoc} */
    @Override
    public void setBuild(IBuildInfo buildInfo) {
        super.setBuild(buildInfo);
    }

    /** Sets include-filters for the compatibility test */
    public void setIncludeFilter(Set<String> includeFilters) {
        mIncludeFilters.addAll(includeFilters);
    }

    /** Gets a copy of include-filters for the compatibility test */
    protected Set<String> getIncludeFilter() {
        return new HashSet<String>(mIncludeFilters);
    }

    /** Sets exclude-filters for the compatibility test */
    public void setExcludeFilter(Set<String> excludeFilters) {
        mExcludeFilters.addAll(excludeFilters);
    }

    /** Gets a copy of exclude-filters for the compatibility test */
    protected Set<String> getExcludeFilter() {
        return new HashSet<String>(mExcludeFilters);
    }

    /** Returns the current {@link SuiteModuleLoader}. */
    public SuiteModuleLoader getModuleLoader() {
        return mModuleRepo;
    }

    /** Adds module args */
    public void addModuleArgs(Set<String> moduleArgs) {
        mModuleArgs.addAll(moduleArgs);
    }

    /** Add config patterns */
    public void addConfigPatterns(List<String> patterns) {
        mConfigPatterns.addAll(patterns);
    }

    /**
     * Create the {@link SuiteModuleLoader} responsible to load the {@link IConfiguration} and
     * assign them some of the options.
     *
     * @param includeFiltersFormatted The formatted and parsed include filters.
     * @param excludeFiltersFormatted The formatted and parsed exclude filters.
     * @param testArgs the list of test ({@link IRemoteTest}) arguments.
     * @param moduleArgs the list of module arguments.
     * @return the created {@link SuiteModuleLoader}.
     */
    public SuiteModuleLoader createModuleLoader(
            Map<String, List<SuiteTestFilter>> includeFiltersFormatted,
            Map<String, List<SuiteTestFilter>> excludeFiltersFormatted,
            List<String> testArgs,
            List<String> moduleArgs) {
        return new SuiteModuleLoader(
                includeFiltersFormatted, excludeFiltersFormatted, testArgs, moduleArgs);
    }

    /**
     * Sets the include/exclude filters up based on if a module name was given.
     *
     * @throws FileNotFoundException if any file is not found.
     */
    protected void setupFilters(File testsDir) throws FileNotFoundException {
        if (mModuleName != null) {
            // If this option (-m / --module) is set only the matching unique module should run.
            Set<File> modules =
                    SuiteModuleLoader.getModuleNamesMatching(
                            testsDir, mSuitePrefix, String.format(".*%s.*.config", mModuleName));
            // If multiple modules match, do exact match.
            if (modules.size() > 1) {
                Set<File> newModules = new HashSet<>();
                String exactModuleName = String.format("%s.config", mModuleName);
                for (File module : modules) {
                    if (module.getName().equals(exactModuleName)) {
                        newModules.add(module);
                        modules = newModules;
                        break;
                    }
                }
            }
            if (modules.size() == 0) {
                throw new IllegalArgumentException(
                        String.format("No modules found matching %s", mModuleName));
            } else if (modules.size() > 1) {
                throw new IllegalArgumentException(
                        String.format(
                                "Multiple modules found matching %s:\n%s\nWhich one did you "
                                        + "mean?\n",
                                mModuleName, ArrayUtil.join("\n", modules)));
            } else {
                File mod = modules.iterator().next();
                String moduleName = mod.getName().replace(".config", "");
                checkFilters(mIncludeFilters, moduleName);
                checkFilters(mExcludeFilters, moduleName);
                mIncludeFilters.add(
                        new SuiteTestFilter(getRequestedAbi(), moduleName, mTestName).toString());
            }
        } else if (mTestName != null) {
            throw new IllegalArgumentException(
                    "Test name given without module name. Add --module <module-name>");
        }
    }

    @Override
    void cleanUpSuiteSetup() {
        super.cleanUpSuiteSetup();
        // Clean the filters because at that point they have been applied to the runners.
        // This can save several GB of memories during sharding.
        mIncludeFilters.clear();
        mExcludeFilters.clear();
        mIncludeFiltersParsed.clear();
        mExcludeFiltersParsed.clear();
    }

    /* Helper method designed to remove filters in a list not applicable to the given module */
    private static void checkFilters(Set<String> filters, String moduleName) {
        Set<String> cleanedFilters = new HashSet<String>();
        for (String filter : filters) {
            SuiteTestFilter filterObject = SuiteTestFilter.createFrom(filter);
            String filterName = filterObject.getName();
            String filterBaseName = filterObject.getBaseName();
            if (moduleName.equals(filterName) || moduleName.equals(filterBaseName)) {
                cleanedFilters.add(filter); // Module name matches, filter passes
            }
        }
        filters.clear();
        filters.addAll(cleanedFilters);
    }

    /* Return a {@link boolean} for the setting of prioritize-host-config.*/
    boolean getPrioritizeHostConfig() {
        return mPrioritizeHostConfig;
    }

    /**
     * Set option prioritize-host-config.
     *
     * @param prioritizeHostConfig true to prioritize host config, i.e., run host test if possible.
     */
    @VisibleForTesting
    protected void setPrioritizeHostConfig(boolean prioritizeHostConfig) {
        mPrioritizeHostConfig = prioritizeHostConfig;
    }
}
