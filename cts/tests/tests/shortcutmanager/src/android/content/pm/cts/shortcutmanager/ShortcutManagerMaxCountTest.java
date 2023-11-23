/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.content.pm.cts.shortcutmanager;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicShortcutCountExceeded;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.retryUntil;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.setDefaultLauncher;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.compatibility.common.util.CddTest;

@CddTest(requirement="3.8.1/C-4-1")
@SmallTest
public class ShortcutManagerMaxCountTest extends ShortcutManagerCtsTestsBase {
    /**
     * Basic tests: single app, single activity, no manifest shortcuts.
     */
    public void testNumDynamicShortcuts() {
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            assertTrue(getManager().setDynamicShortcuts(list(makeShortcut("s1"))));
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8"),
                    makeShortcut("s9"),
                    makeShortcut("s10")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled();
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1x"),
                    makeShortcut("s2x"),
                    makeShortcut("s3x"),
                    makeShortcut("s4x"),
                    makeShortcut("s5x"),
                    makeShortcut("s6x"),
                    makeShortcut("s7x"),
                    makeShortcut("s8x"),
                    makeShortcut("s9x"),
                    makeShortcut("s10x")
            )));

            assertDynamicShortcutCountExceeded(() -> {
                getManager().setDynamicShortcuts(list(
                        makeShortcut("s1y"),
                        makeShortcut("s2y"),
                        makeShortcut("s3y"),
                        makeShortcut("s4y"),
                        makeShortcut("s5y"),
                        makeShortcut("s6y"),
                        makeShortcut("s7y"),
                        makeShortcut("s8y"),
                        makeShortcut("s9y"),
                        makeShortcut("s10y"),
                        makeShortcut("s11y")));
            });
            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1x", "s2x", "s3x", "s4x", "s5x", "s6x", "s7x", "s8x", "s9x", "s10x")
                    .areAllDynamic()
                    .areAllEnabled();

            assertDynamicShortcutCountExceeded(() -> {
                getManager().addDynamicShortcuts(list(
                        makeShortcut("s1y")));
            });
            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1x", "s2x", "s3x", "s4x", "s5x", "s6x", "s7x", "s8x", "s9x", "s10x")
                    .areAllDynamic()
                    .areAllEnabled();
            getManager().removeDynamicShortcuts(list("s10x"));
            assertTrue(getManager().addDynamicShortcuts(list(
                    makeShortcut("s1y"))));

            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1x", "s2x", "s3x", "s4x", "s5x", "s6x", "s7x", "s8x", "s9x", "s1y")
                    .areAllDynamic()
                    .areAllEnabled();

            getManager().removeAllDynamicShortcuts();

            assertTrue(getManager().addDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8"),
                    makeShortcut("s9"),
                    makeShortcut("s10")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled();
        });
    }

    /**
     * Manifest shortcuts are included in the count too.
     */
    public void testWithManifest() throws Exception {
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            enableManifestActivity("Launcher_manifest_1", true);
            enableManifestActivity("Launcher_manifest_2", true);

            retryUntil(() -> getManager().getManifestShortcuts().size() == 3,
                    "Manifest shortcuts didn't show up");

        });

        runWithCallerWithStrictMode(mPackageContext1, () -> {
            assertWith(getManager().getManifestShortcuts())
                    .haveIds("ms1", "ms21", "ms22")
                    .areAllManifest()
                    .areAllEnabled()
                    .areAllNotPinned()

                    .selectByIds("ms1")
                    .forAllShortcuts(sa -> {
                        assertEquals(getActivity("Launcher_manifest_1"), sa.getActivity());
                    })

                    .revertToOriginalList()
                    .selectByIds("ms21", "ms22")
                    .forAllShortcuts(sa -> {
                        assertEquals(getActivity("Launcher_manifest_2"), sa.getActivity());
                    });

        });

        // Note since max counts is per activity, testNumDynamicShortcuts_single should just pass.
        testNumDynamicShortcuts();

        // Launcher_manifest_1 has one manifest, so can only add 9 dynamic shortcuts.
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            setTargetActivityOverride("Launcher_manifest_1");

            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8"),
                    makeShortcut("s9")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher_manifest_1"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9")
                    .areAllEnabled();

            assertDynamicShortcutCountExceeded(() -> getManager().setDynamicShortcuts(list(
                    makeShortcut("s1x"),
                    makeShortcut("s2x"),
                    makeShortcut("s3x"),
                    makeShortcut("s4x"),
                    makeShortcut("s5x"),
                    makeShortcut("s6x"),
                    makeShortcut("s7x"),
                    makeShortcut("s8x"),
                    makeShortcut("s9x"),
                    makeShortcut("s10x")
            )));
            // Not changed.
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher_manifest_1"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9")
                    .areAllEnabled();
        });

        // Launcher_manifest_2 has two manifests, so can only add 8.
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            setTargetActivityOverride("Launcher_manifest_2");

            assertTrue(getManager().addDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher_manifest_2"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8")
                    .areAllEnabled();

            assertDynamicShortcutCountExceeded(() -> getManager().addDynamicShortcuts(list(
                    makeShortcut("s1x")
            )));
            // Not added.
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher_manifest_2"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8")
                    .areAllEnabled();
        });
    }

    public void testChangeActivity() {
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            setTargetActivityOverride("Launcher");
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8"),
                    makeShortcut("s9"),
                    makeShortcut("s10")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled();

            setTargetActivityOverride("Launcher2");

            assertTrue(getManager().addDynamicShortcuts(list(
                    makeShortcut("s1b"),
                    makeShortcut("s2b"),
                    makeShortcut("s3b"),
                    makeShortcut("s4b"),
                    makeShortcut("s5b"),
                    makeShortcut("s6b"),
                    makeShortcut("s7b"),
                    makeShortcut("s8b"),
                    makeShortcut("s9b"),
                    makeShortcut("s10b")
            )));
            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByActivity(getActivity("Launcher2"))
                    .haveIds("s1b", "s2b", "s3b", "s4b", "s5b", "s6b", "s7b", "s8b", "s9b", "s10b")
                    .areAllDynamic()
                    .areAllEnabled();

            // Moving one from L1 to L2 is not allowed.
            assertDynamicShortcutCountExceeded(() -> getManager().updateShortcuts(list(
                    makeShortcut("s1", getActivity("Launcher2"))
            )));

            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher"))
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByActivity(getActivity("Launcher2"))
                    .haveIds("s1b", "s2b", "s3b", "s4b", "s5b", "s6b", "s7b", "s8b", "s9b", "s10b")
                    .areAllDynamic()
                    .areAllEnabled();

            // But swapping shortcuts will work.
            assertTrue(getManager().updateShortcuts(list(
                    makeShortcut("s1", getActivity("Launcher2")),
                    makeShortcut("s1b", getActivity("Launcher"))
            )));

            assertWith(getManager().getDynamicShortcuts())
                    .selectByActivity(getActivity("Launcher"))
                    .haveIds("s1b", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllDynamic()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByActivity(getActivity("Launcher2"))
                    .haveIds("s1", "s2b", "s3b", "s4b", "s5b", "s6b", "s7b", "s8b", "s9b", "s10b")
                    .areAllDynamic()
                    .areAllEnabled();
        });
    }

    public void testWithPinned() {
        runWithCallerWithStrictMode(mPackageContext1, () -> {
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5"),
                    makeShortcut("s6"),
                    makeShortcut("s7"),
                    makeShortcut("s8"),
                    makeShortcut("s9"),
                    makeShortcut("s10")
            )));
        });

        setDefaultLauncher(getInstrumentation(), mLauncherContext1);

        runWithCallerWithStrictMode(mLauncherContext1, () -> {
            getLauncherApps().pinShortcuts(mPackageContext1.getPackageName(),
                    list("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"),
                    getUserHandle());
        });

        runWithCallerWithStrictMode(mPackageContext1, () -> {
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1b"),
                    makeShortcut("s2b"),
                    makeShortcut("s3b"),
                    makeShortcut("s4b"),
                    makeShortcut("s5b"),
                    makeShortcut("s6b"),
                    makeShortcut("s7b"),
                    makeShortcut("s8b"),
                    makeShortcut("s9b"),
                    makeShortcut("s10b")
            )));

            assertWith(getManager().getDynamicShortcuts())
                    .haveIds("s1b", "s2b", "s3b", "s4b", "s5b", "s6b", "s7b", "s8b", "s9b", "s10b")
                    .areAllEnabled()
                    .areAllNotPinned();

            assertWith(getManager().getPinnedShortcuts())
                    .haveIds("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10")
                    .areAllEnabled()
                    .areAllNotDynamic();
        });
    }
}
