package android.security;

import android.system.Os;
import android.test.AndroidTestCase;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;
import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

abstract class SELinuxTargetSdkTestBase extends AndroidTestCase
{
    static {
        System.loadLibrary("ctsselinux_jni");
    }

    protected static String getFile(String filename) throws IOException {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(filename));
            return in.readLine().trim();
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    protected static void noExecuteOnly() throws IOException {
        String[] maps = {"^[0-9a-z]+-[0-9a-z]+\\s+--xp.*\\/apex\\/com\\.android\\.runtime\\/.*",
            "^[0-9a-z]+-[0-9a-z]+\\s+--xp.*\\/system\\/.*"};
        for (String map : maps) {
            final Pattern mapsPattern = Pattern.compile(map);
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    Matcher m = mapsPattern.matcher(line);
                    assertFalse("System provided libraries should be not be marked execute-only " +
                           "for apps with targetSdkVersion<Q, but an execute-only segment was " +
                           "found:\n" + line, m.matches());
                }

            } finally {
                reader.close();
            }
        }
    }

    protected static String getProperty(String property)
            throws IOException {
        Process process = new ProcessBuilder("getprop", property).start();
        Scanner scanner = null;
        String line = "";
        try {
            scanner = new Scanner(process.getInputStream());
            line = scanner.nextLine();
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
        return line;
    }

    /**
     * Verify that net.dns properties may not be read
     */
    protected static void noDns() throws IOException {
        String[] dnsProps = {"net.dns1", "net.dns2", "net.dns3", "net.dns4"};
        for(int i = 0; i < dnsProps.length; i++) {
            String dns = getProperty(dnsProps[i]);
            assertEquals("DNS properties may not be readable by apps past " +
                    "targetSdkVersion 26", "", dns);
        }
    }

    /**
     * Check expectations of being able to read/execute dex2oat.
     */
    protected static void checkDex2oatAccess(boolean expectedAllowed) throws Exception {
        // First check whether there is an Android Runtime APEX dex2oat binary.
        File dex2oatRuntimeApexBinary = new File("/apex/com.android.runtime/bin/dex2oat");
        if (dex2oatRuntimeApexBinary.exists()) {
          checkDex2oatBinaryAccess(dex2oatRuntimeApexBinary, expectedAllowed);
        }
        // Also check whether there is a "legacy" system binary.
        File dex2oatSystemBinary = new File("/system/bin/dex2oat");
        if (dex2oatSystemBinary.exists()) {
          checkDex2oatBinaryAccess(dex2oatSystemBinary, expectedAllowed);
        }
    }

    private static void checkDex2oatBinaryAccess(File dex2oatBinary, boolean expectedAllowed)
        throws Exception {
        // Check permissions.
        assertEquals(expectedAllowed, dex2oatBinary.canRead());
        assertEquals(expectedAllowed, dex2oatBinary.canExecute());

        // Try to execute dex2oat.
        try {
            Runtime rt = Runtime.getRuntime();
            Process p = rt.exec(dex2oatBinary.getAbsolutePath());
            p.waitFor();
            assertEquals(expectedAllowed, true);
        } catch (IOException ex) {
            assertEquals(expectedAllowed, false);
            assertEquals(ex.getMessage(),
                    "Cannot run program \"" + dex2oatBinary.getAbsolutePath() +
                    "\": error=13, Permission denied");
        }
    }

    /**
     * Verify that selinux context is the expected domain based on
     * targetSdkVersion,
     */
    protected void appDomainContext(String contextRegex, String errorMsg) throws IOException {
        Pattern p = Pattern.compile(contextRegex);
        Matcher m = p.matcher(getFile("/proc/self/attr/current"));
        String context = getFile("/proc/self/attr/current");
        String msg = errorMsg + context;
        assertTrue(msg, m.matches());
    }

    /**
     * Verify that selinux context is the expected type based on
     * targetSdkVersion,
     */
    protected void appDataContext(String contextRegex, String errorMsg) throws Exception {
        Pattern p = Pattern.compile(contextRegex);
        File appDataDir = getContext().getFilesDir();
        Matcher m = p.matcher(getFileContext(appDataDir.getAbsolutePath()));
        String context = getFileContext(appDataDir.getAbsolutePath());
        String msg = errorMsg + context;
        assertTrue(msg, m.matches());
    }

    protected boolean canExecuteFromHomeDir() throws Exception {
        File appDataDir = getContext().getFilesDir();
        File temp = File.createTempFile("badbin", "exe", appDataDir);
        temp.deleteOnExit();
        String path = temp.getPath();
        Os.chmod(path, 0700);
        try {
            Process process = new ProcessBuilder(path).start();
        } catch (IOException e) {
            return !e.toString().contains("Permission denied");
        } finally {
            temp.delete();
        }
        return true;
    }

    private static final native String getFileContext(String path);
}
