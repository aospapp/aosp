/*
 * Copyright (C) 2010 The Android Open Source Project
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

package vogar.android;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import vogar.Classpath;
import vogar.HostFileCache;
import vogar.Language;
import vogar.Log;
import vogar.Md5Cache;
import vogar.ModeId;
import vogar.commands.Command;
import vogar.commands.Mkdir;
import vogar.util.Strings;

/**
 * Android SDK commands such as adb, aapt and dx.
 */
public class AndroidSdk {

    private final Log log;
    private final Mkdir mkdir;
    private final File[] compilationClasspath;
    private final String androidJarPath;
    private final String desugarJarPath;
    private final Md5Cache dexCache;
    private final Language language;

    public static Collection<File> defaultExpectations() {
        return Collections.singletonList(new File("libcore/expectations/knownfailures.txt"));
    }

    /**
     * Create an {@link AndroidSdk}.
     *
     * <p>Searches the PATH used to run this and scans the file system in order to determine the
     * compilation class path and android jar path.
     */
    public static AndroidSdk createAndroidSdk(
            Log log, Mkdir mkdir, ModeId modeId, boolean useJack, Language language) {
        List<String> path = new Command.Builder(log).args("which", "dx")
                .permitNonZeroExitStatus(true)
                .execute();
        if (path.isEmpty()) {
            throw new RuntimeException("dx not found");
        }
        File dx = new File(path.get(0)).getAbsoluteFile();
        String parentFileName = getParentFileNOrLast(dx, 1).getName();

        List<String> adbPath = new Command.Builder(log)
                .args("which", "adb")
                .permitNonZeroExitStatus(true)
                .execute();

        File adb;
        if (!adbPath.isEmpty()) {
            adb = new File(adbPath.get(0));
        } else {
            adb = null;  // Could not find adb.
        }

        /*
         * Determine if we are running with a provided SDK or in the AOSP source tree.
         *
         * On Android SDK v23 (Marshmallow) the structure looks like:
         *  <sdk>/build-tools/23.0.1/aapt
         *  <sdk>/platform-tools/adb
         *  <sdk>/build-tools/23.0.1/dx
         *  <sdk>/platforms/android-23/android.jar
         *
         * Android build tree (target):
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/aapt
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/adb
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/dx
         *  ${ANDROID_BUILD_TOP}/out/host/linux-x86/bin/desugar.jar
         *  ${ANDROID_BUILD_TOP}/out/target/common/obj/JAVA_LIBRARIES/core-libart_intermediates
         *      /classes.jar
         */

        File[] compilationClasspath;
        String androidJarPath;
        String desugarJarPath = null;

        // Accept that we are running in an SDK if the user has added the build-tools or
        // platform-tools to their path.
        boolean dxSdkPathValid = "build-tools".equals(getParentFileNOrLast(dx, 2).getName());
        boolean isAdbPathValid = (adb != null) &&
                "platform-tools".equals(getParentFileNOrLast(adb, 1).getName());
        if (dxSdkPathValid || isAdbPathValid) {
            File sdkRoot = dxSdkPathValid ? getParentFileNOrLast(dx, 3)  // if dx path invalid then
                                          : getParentFileNOrLast(adb, 2);  // adb must be valid.
            File newestPlatform = getNewestPlatform(sdkRoot);
            log.verbose("Using android platform: " + newestPlatform);
            compilationClasspath = new File[] { new File(newestPlatform, "android.jar") };
            androidJarPath = new File(newestPlatform.getAbsolutePath(), "android.jar")
                    .getAbsolutePath();
            log.verbose("using android sdk: " + sdkRoot);

            if (!useJack) {
              // There must be a desugar.jar in the same directory as dx.
              String dxParentFileName = getParentFileNOrLast(dx, 1).getName();
              desugarJarPath = dxParentFileName + "/desugar.jar";
              File desugarJarFile = new File(desugarJarPath);
              if (!desugarJarFile.exists()) {
                  throw new RuntimeException("Could not find " + desugarJarPath);
              }
            }
        } else if ("bin".equals(parentFileName)) {
            log.verbose("Using android source build mode to find dependencies.");
            String tmpJarPath = "prebuilts/sdk/current/android.jar";
            String androidBuildTop = System.getenv("ANDROID_BUILD_TOP");
            if (!com.google.common.base.Strings.isNullOrEmpty(androidBuildTop)) {
                tmpJarPath = androidBuildTop + "/prebuilts/sdk/current/android.jar";
            } else {
                log.warn("Assuming current directory is android build tree root.");
            }
            androidJarPath = tmpJarPath;

            String outDir = System.getenv("OUT_DIR");
            if (Strings.isNullOrEmpty(outDir)) {
                if (Strings.isNullOrEmpty(androidBuildTop)) {
                    outDir = ".";
                    log.warn("Assuming we are in android build tree root to find libraries.");
                } else {
                    log.verbose("Using ANDROID_BUILD_TOP to find built libraries.");
                    outDir = androidBuildTop;
                }
                outDir += "/out/";
            } else {
                log.verbose("Using OUT_DIR environment variable for finding built libs.");
                outDir += "/";
            }

            if (!useJack) {
                File desugarJar = null;
                String desugarPattern = outDir + "host/linux-x86/framework/desugar.jar";
                desugarJar = new File(desugarPattern);

                if (!desugarJar.exists()) {
                    throw new RuntimeException("Could not find " + desugarPattern);
                }

                desugarJarPath = desugarJar.getPath();
            }

            String pattern = outDir + "target/common/obj/JAVA_LIBRARIES/%s_intermediates/classes";
            if (modeId.isHost()) {
                pattern = outDir + "host/common/obj/JAVA_LIBRARIES/%s_intermediates/classes";
            }
            pattern += ((useJack) ? ".jack" : ".jar");

            String[] jarNames = modeId.getJarNames();
            compilationClasspath = new File[jarNames.length];
            for (int i = 0; i < jarNames.length; i++) {
                String jar = jarNames[i];
                compilationClasspath[i] = new File(String.format(pattern, jar));
            }
        } else {
            throw new RuntimeException("Couldn't derive Android home from " + dx);
        }

        return new AndroidSdk(log, mkdir, compilationClasspath, androidJarPath, desugarJarPath,
                new HostFileCache(log, mkdir), language);
    }

    @VisibleForTesting
    AndroidSdk(Log log, Mkdir mkdir, File[] compilationClasspath, String androidJarPath,
               String desugarJarPath, HostFileCache hostFileCache, Language language) {
        this.log = log;
        this.mkdir = mkdir;
        this.compilationClasspath = compilationClasspath;
        this.androidJarPath = androidJarPath;
        this.desugarJarPath = desugarJarPath;
        this.dexCache = new Md5Cache(log, "dex", hostFileCache);
        this.language = language;
    }

    // Goes up N levels in the filesystem hierarchy. Return the last file that exists if this goes
    // past /.
    private static File getParentFileNOrLast(File f, int n) {
        File lastKnownExists = f;
        for (int i = 0; i < n; i++) {
            File parentFile = lastKnownExists.getParentFile();
            if (parentFile == null) {
                return lastKnownExists;
            }
            lastKnownExists = parentFile;
        }
        return lastKnownExists;
    }

    /**
     * Returns the platform directory that has the highest API version. API
     * platform directories are named like "android-9" or "android-11".
     */
    private static File getNewestPlatform(File sdkRoot) {
        File newestPlatform = null;
        int newestPlatformVersion = 0;
        File[] platforms = new File(sdkRoot, "platforms").listFiles();
        if (platforms != null) {
            for (File platform : platforms) {
                try {
                    int version =
                            Integer.parseInt(platform.getName().substring("android-".length()));
                    if (version > newestPlatformVersion) {
                        newestPlatform = platform;
                        newestPlatformVersion = version;
                    }
                } catch (NumberFormatException ignore) {
                    // Ignore non-numeric preview versions like android-Honeycomb
                }
            }
        }
        if (newestPlatform == null) {
            throw new IllegalStateException("Cannot find newest platform in " + sdkRoot);
        }
        return newestPlatform;
    }

    public static Collection<File> defaultSourcePath() {
        return filterNonExistentPathsFrom("libcore/support/src/test/java",
                                          "external/mockwebserver/src/main/java/");
    }

    private static Collection<File> filterNonExistentPathsFrom(String... paths) {
        ArrayList<File> result = new ArrayList<File>();
        String buildRoot = System.getenv("ANDROID_BUILD_TOP");
        for (String path : paths) {
            File file = new File(buildRoot, path);
            if (file.exists()) {
                result.add(file);
            }
        }
        return result;
    }

    public File[] getCompilationClasspath() {
        return compilationClasspath;
    }

    /**
     * Converts all the .class files on 'classpath' into a dex file written to 'output'.
     *
     * @param multidex could the output be more than 1 dex file?
     * @param output the File for the classes.dex that will be generated as a result of this call.
     * @param outputTempDir a temporary directory which can store intermediate files generated.
     * @param classpath a list of files/directories containing .class files that are
     *                  merged together and converted into the output (dex) file.
     * @param dependentCp classes that are referenced in classpath but are not themselves on the
     *                    classpath must be listed in dependentCp, this is required to be able
     *                    resolve all class dependencies. The classes in dependentCp are <i>not</i>
     *                    included in the output dex file.
     */
    public void dex(boolean multidex, File output, File outputTempDir,
            Classpath classpath, Classpath dependentCp) {
        mkdir.mkdirs(output.getParentFile());

        String classpathSubKey = dexCache.makeKey(classpath);
        String cacheKey = null;
        if (classpathSubKey != null) {
            String multidexSubKey = "mdex=" + multidex;
            cacheKey = dexCache.makeKey(classpathSubKey, multidexSubKey);
            boolean cacheHit = dexCache.getFromCache(output, cacheKey);
            if (cacheHit) {
                log.verbose("dex cache hit for " + classpath);
                return;
            }
        }

        // Call desugar first to remove invoke-dynamic LambdaMetaFactory usage,
        // which ART doesn't support.
        List<String> desugarOutputFilePaths = desugar(outputTempDir, classpath, dependentCp);

        /*
         * We pass --core-library so that we can write tests in the
         * same package they're testing, even when that's a core
         * library package. If you're actually just using this tool to
         * execute arbitrary code, this has the unfortunate
         * side-effect of preventing "dx" from protecting you from
         * yourself.
         *
         * Memory options pulled from build/core/definitions.mk to
         * handle large dx input when building dex for APK.
         */
        Command.Builder builder = new Command.Builder(log)
                .args("dx")
                .args("-JXms16M")
                .args("-JXmx1536M")
                .args("--min-sdk-version=" + language.getMinApiLevel());
        if (multidex) {
            builder.args("--multi-dex");
        }
        builder.args("--dex")
                .args("--output=" + output)
                .args("--core-library")
                .args(desugarOutputFilePaths);
        builder.execute();
        dexCache.insert(cacheKey, output);
    }

    // Runs desugar on classpath as the input with dependentCp as the classpath_entry.
    // Returns the generated output list of files.
    private List<String> desugar(File outputTempDir, Classpath classpath, Classpath dependentCp) {
        Command.Builder builder = new Command.Builder(log)
                .args("java", "-jar", desugarJarPath);

        // Ensure that libcore is on the bootclasspath for desugar,
        // otherwise it tries to use the java command's bootclasspath.
        for (File f : compilationClasspath) {
            builder.args("--bootclasspath_entry", f.getPath());
        }

        // Desugar needs to actively resolve classes that the original inputs
        // were compiled against. Dx does not; so it doesn't use dependentCp.
        for (File f : dependentCp.getElements()) {
            builder.args("--classpath_entry", f.getPath());
        }

        builder.args("--core_library")
                .args("--min_sdk_version", language.getMinApiLevel());

        // Build the -i (input) and -o (output) arguments.
        // Every input from classpath corresponds to a new output temp file into
        // desugarTempDir.
        File desugarTempDir;
        {
            // Generate a temporary list of files that correspond to the 'classpath';
            // desugar will then convert the files in 'classpath' into 'desugarClasspath'.
            if (!outputTempDir.isDirectory()) {
                throw new AssertionError(
                        "outputTempDir must be a directory: " + outputTempDir.getPath());
            }

            String desugarTempDirPath = outputTempDir.getPath() + "/desugar";
            desugarTempDir = new File(desugarTempDirPath);
            desugarTempDir.mkdirs();
            if (!desugarTempDir.exists()) {
                throw new AssertionError(
                        "desugarTempDir; failed to create " + desugarTempDirPath);
            }
        }

        // Create unique file names to support non-unique classpath base names.
        //
        // For example:
        //
        // Classpath("/x/y.jar:/z/y.jar:/a/b.jar") ->
        // Output Files("${tmp}/0y.jar:${tmp}/1y.jar:${tmp}/2b.jar")
        int uniqueCounter = 0;
        List<String> desugarOutputFilePaths = new ArrayList<String>();

        for (File desugarInput : classpath.getElements()) {
            String tmpName = uniqueCounter + desugarInput.getName();
            ++uniqueCounter;

            String desugarOutputPath = desugarTempDir.getPath() + "/" + tmpName;
            desugarOutputFilePaths.add(desugarOutputPath);

            builder.args("-i", desugarInput.getPath())
                    .args("-o", desugarOutputPath);
        }

        builder.execute();

        return desugarOutputFilePaths;
    }

    public void packageApk(File apk, File manifest) {
        new Command(log, "aapt",
                "package",
                "-F", apk.getPath(),
                "-M", manifest.getPath(),
                "-I", androidJarPath,
                "--version-name", "1.0",
                "--version-code", "1").execute();
    }

    public void addToApk(File apk, File dex) {
        new Command(log, "aapt", "add", "-k", apk.getPath(), dex.getPath()).execute();
    }

    public void install(File apk) {
        new Command(log, "adb", "install", "-r", apk.getPath()).execute();
    }

    public void uninstall(String packageName) {
        new Command.Builder(log)
                .args("adb", "uninstall", packageName)
                .permitNonZeroExitStatus(true)
                .execute();
    }
}
