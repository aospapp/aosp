// Copyright 2022 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package hidl

import (
	"testing"

	"android/soong/android"
	"android/soong/bp2build"
	"android/soong/cc"
)

func runHidlInterfaceTestCase(t *testing.T, tc bp2build.Bp2buildTestCase) {
	t.Helper()
	bp2build.RunBp2BuildTestCase(
		t,
		func(ctx android.RegistrationContext) {
			ctx.RegisterModuleType("cc_defaults", func() android.Module { return cc.DefaultsFactory() })
			ctx.RegisterModuleType("hidl_interface", HidlInterfaceFactory)
			ctx.RegisterModuleType("hidl_package_root", HidlPackageRootFactory)
		},
		tc,
	)
}

func TestHidlInterface(t *testing.T) {
	runHidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `hidl_interface with common usage of properties`,
		Blueprint: `
hidl_package_root {
		name: "android.hardware",
		use_current: true,
}
cc_defaults {
		name: "hidl-module-defaults",
}
hidl_interface {
		name: "android.hardware.nfc@1.0",
		srcs: ["types.hal", "IBase.hal"],
		root: "android.hardware",
		gen_java: false,
}
hidl_interface {
		name: "android.hardware.nfc@1.1",
		srcs: ["types.hal", "INfc.hal"],
		interfaces: ["android.hardware.nfc@1.0"],
		root: "android.hardware",
		gen_java: false,
}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("hidl_interface", "android.hardware.nfc@1.0", bp2build.AttrNameToString{
				"min_sdk_version":     `"29"`,
				"root":                `"android.hardware"`,
				"root_interface_file": `":current.txt"`,
				"srcs": `[
        "types.hal",
        "IBase.hal",
    ]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("hidl_interface", "android.hardware.nfc@1.1", bp2build.AttrNameToString{
				"deps":                `[":android.hardware.nfc@1.0"]`,
				"min_sdk_version":     `"29"`,
				"root":                `"android.hardware"`,
				"root_interface_file": `":current.txt"`,
				"srcs": `[
        "types.hal",
        "INfc.hal",
    ]`,
			}),
		},
	})
}

func TestHidlInterfacePackageRootInAnotherBp(t *testing.T) {
	runHidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `hidl_interface with common usage of properties`,
		Filesystem: map[string]string{
			"foo/bar/Android.bp": `
hidl_package_root {
		name: "android.hardware",
		use_current: true,
}`},
		Blueprint: `
cc_defaults {
		name: "hidl-module-defaults",
}
hidl_interface {
		name: "android.hardware.nfc@1.0",
		srcs: ["types.hal", "IBase.hal"],
		root: "android.hardware",
		gen_java: false,
}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("hidl_interface", "android.hardware.nfc@1.0", bp2build.AttrNameToString{
				"min_sdk_version":     `"29"`,
				"root":                `"android.hardware"`,
				"root_interface_file": `"//foo/bar:current.txt"`,
				"srcs": `[
        "types.hal",
        "IBase.hal",
    ]`,
			}),
		},
	})
}
