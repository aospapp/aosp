/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.security.cts;

import android.content.Context;
import android.content.res.AssetManager;
import android.security.cts.SELinuxPolicyRule;
import android.test.AndroidTestCase;

import junit.framework.TestCase;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;

/**
 * Verify that the SELinux configuration is sane.
 */
public class SELinuxTest extends AndroidTestCase {

    static {
        System.loadLibrary("ctssecurity_jni");
    }

    public void testMyJni() {
        try {
            checkSELinuxAccess(null, null, null, null, null);
            fail("checkSELinuxAccess should have thrown");
        } catch (NullPointerException e) {
            // expected
        }
        try {
            checkSELinuxContext(null);
            fail("checkSELinuxContext should have thrown");
        } catch (NullPointerException e) {
            // expected
        }
    }

    public void testCheckAccessSane() {
        assertFalse(checkSELinuxAccess("a", "b", "c", "d", "e"));
    }

    public void testCheckContextSane() {
        assertFalse(checkSELinuxContext("a"));
    }

    public void testZygoteContext() {
        assertTrue(checkSELinuxContext("u:r:zygote:s0"));
    }

    public void testZygote() {
        assertFalse(checkSELinuxAccess("u:r:zygote:s0", "u:object_r:runas_exec:s0", "file", "getattr", "/system/bin/run-as"));
        // Also check init, just as a sanity check (init is unconfined, so it should pass)
        assertTrue(checkSELinuxAccess("u:r:init:s0", "u:object_r:runas_exec:s0", "file", "getattr", "/system/bin/run-as"));
    }

    public void testNoBooleans() throws Exception {
        // Intentionally not using JNI bindings to keep things simple
        File[] files = new File("/sys/fs/selinux/booleans/").listFiles();
        assertEquals(0, files.length);
    }

    /**
     * Verify all of the rules described by the selinux_policy.xml file are in effect.  Allow rules
     * should return access granted, and Neverallow should return access denied.  All checks are run
     * and then a list of specific failed checks is printed.
     */
    public void testSELinuxPolicyFile() throws IOException, XmlPullParserException {
        List<String> failedChecks = new ArrayList<String>();
        Map<String, Boolean> contextsCache = new HashMap<String, Boolean>();
        int invalidContextsCount = 0;
        int totalChecks = 0;
        int totalFailedChecks = 0;
        AssetManager assets = mContext.getAssets();
        InputStream in = assets.open("selinux_policy.xml");
        Collection<SELinuxPolicyRule> rules = SELinuxPolicyRule.readRulesFile(in);
        for (SELinuxPolicyRule r : rules) {
            PolicyFileTestResult result = runRuleChecks(r, contextsCache);
            totalChecks += result.numTotalChecks;
            if (result.numFailedChecks != 0) {
                totalFailedChecks += result.numFailedChecks;

                /* print failures to log, so as not to run OOM in the event of large policy mismatch,
                   but record actual rule type and number */
                failedChecks.add("SELinux avc rule " + r.type + r.name + " failed " + result.numFailedChecks +
                        " out of " + result.numTotalChecks + " checks.");
                for (String k : result.failedChecks) {
                    System.out.println(r.type + r.name + " failed " + k);
                }
            }
        }
        if (totalFailedChecks != 0) {

            /* print out failed rules, just the rule number and type */
            for (String k : failedChecks) {
                System.out.println(k);
            }
            System.out.println("Failed SELinux Policy Test: " + totalFailedChecks + " failed out of " + totalChecks);
        }
        for (String k : contextsCache.keySet()) {
            if (!contextsCache.get(k)) {
                invalidContextsCount++;
                System.out.println("Invalid SELinux context encountered: " + k);
            }
        }
        System.out.println("SELinuxPolicy Test Encountered: " + invalidContextsCount + " missing contexts out of " + contextsCache.size());
        assertTrue(totalFailedChecks == 0);
    }

    /**
     * A class for containing all of the results we care to know from checking each SELinux rule
     */
    private class PolicyFileTestResult {
        private int numTotalChecks;
        private int numFailedChecks;
        private List<String> failedChecks = new ArrayList<String>();
    }

    private PolicyFileTestResult runRuleChecks(SELinuxPolicyRule r, Map<String, Boolean> contextsCache) {
        PolicyFileTestResult result = new PolicyFileTestResult();

        /* run checks by going through every possible 4-tuple specified by rule.  Start with class
           and perm to allow early-exit based on context. */
        for (String c : r.obj_classes.keySet()) {
            for (String p : r.obj_classes.get(c)) {
                for (String s : r.source_types) {

                    /* check source context */
                    String source_context = createAvcContext(s, false, c, p);
                    if (!contextsCache.containsKey(source_context)) {
                        contextsCache.put(source_context, checkSELinuxContext(source_context));
                    }
                    if (!contextsCache.get(source_context)) {
                        continue;
                    }
                    for (String t : r.target_types) {
                        if (t.equals("self")) {
                            t = s;
                        }

                        /* check target context */
                        String target_context = createAvcContext(t, true, c, p);
                        if (!contextsCache.containsKey(target_context)) {
                            contextsCache.put(target_context, checkSELinuxContext(target_context));
                        }
                        if (!contextsCache.get(target_context)) {
                            continue;
                        }
                        boolean canAccess  = checkSELinuxAccess(source_context, target_context,
                                c, p, "");
                        result.numTotalChecks++;
                        if ((r.type.equals("allow") && !canAccess)
                                || (r.type.equals("neverallow") && canAccess)) {
                            String failureNotice = s + ", " + t + ", " + c + ", " + p;
                            result.numFailedChecks++;
                            result.failedChecks.add(failureNotice);
                        }
                    }
                }
            }
        }
        return result;
    }

    /* createAvcContext - currently uses class type and perm to determine user, role and mls values.
     *
     * @param target - false if source domain, true if target.
     */
    private String createAvcContext(String domain, boolean target,
            String obj_class, String perm) {
        String usr = "u";
        String role;

        /* understand role labeling better */
        if (obj_class.equals("filesystem") && perm.equals("associate")) {
            role = "object_r";
        } else if(obj_class.equals("process") || obj_class.endsWith("socket")) {
            role = "r";
        } else if (target) {
            role = "object_r";
        } else {
            role = "r";
        }
        return String.format("%s:%s:%s:s0", usr, role, domain);
    }

    private static native boolean checkSELinuxAccess(String scon, String tcon, String tclass, String perm, String extra);

    private static native boolean checkSELinuxContext(String con);
}
