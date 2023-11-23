package aidl

import (
	"android/soong/android"
	"android/soong/bp2build"
	"testing"
)

func runAidlInterfaceTestCase(t *testing.T, tc bp2build.Bp2buildTestCase) {
	t.Helper()
	bp2build.RunBp2BuildTestCase(
		t,
		func(ctx android.RegistrationContext) {
			ctx.RegisterModuleType("aidl_interface", AidlInterfaceFactory)
			ctx.RegisterModuleType("aidl_interface_headers", AidlInterfaceHeadersFactory)
		},
		tc,
	)
}

func TestAidlInterfaceHeaders(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface_headers`,
		Blueprint: `
			aidl_interface_headers {
				name: "aidl-interface-headers",
				include_dir: "src",
				srcs: [
					"src/A.aidl",
				],
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_library", "aidl-interface-headers", bp2build.AttrNameToString{
				"strip_import_prefix": `"src"`,
				"hdrs":                `["src/A.aidl"]`,
			}),
		},
	})
}

func TestAidlInterface(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with single "latest" aidl_interface import`,
		Blueprint: `
			aidl_interface_headers {
				name: "aidl-interface-headers",
			}
			aidl_interface {
				name: "aidl-interface-import",
				versions: [
					"1",
					"2",
				],
			}
			aidl_interface {
				name: "aidl-interface1",
				flags: ["--flag1"],
				imports: [
				"aidl-interface-import-V1",
				],
				headers: [
					"aidl-interface-headers",
				],
				versions: [
					"1",
					"2",
					"3",
				],
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_library", "aidl-interface-headers", bp2build.AttrNameToString{}),
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface-import", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"versions_with_info": `[
        {
        "version": "1",
    },
        {
        "version": "2",
    },
    ]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"deps":  `[":aidl-interface-headers"]`,
				"flags": `["--flag1"]`,
				"versions_with_info": `[
        {
        "deps": [":aidl-interface-import-V1"],
        "version": "1",
    },
        {
        "deps": [":aidl-interface-import-V1"],
        "version": "2",
    },
        {
        "deps": [":aidl-interface-import-V1"],
        "version": "3",
    },
    ]`,
			}),
		},
	})
}

func TestAidlInterfaceWithNoProperties(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface no properties set`,
		Blueprint: `
			aidl_interface {
				name: "aidl-interface1",
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
			}),
		},
	})
}

func TestAidlInterfaceWithDisabledBackends(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with some backends disabled`,
		Blueprint: `
			aidl_interface {
				name: "aidl-interface1",
				backend: {
					ndk: {
						enabled: false,
					},
					cpp: {
						enabled: false,
					},
				},
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
			}),
		},
	})
}

func TestAidlInterfaceWithLatestImport(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with single "latest" aidl_interface import`,
		Blueprint: `
			aidl_interface {
				name: "aidl-interface-import",
				versions: [
					"1",
					"2",
				],
			}
			aidl_interface {
				name: "aidl-interface1",
				imports: [
					"aidl-interface-import",
				],
				versions: [
					"1",
					"2",
					"3",
				],
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface-import", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"versions_with_info": `[
        {
        "version": "1",
    },
        {
        "version": "2",
    },
    ]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"versions_with_info": `[
        {
        "deps": [":aidl-interface-import-latest"],
        "version": "1",
    },
        {
        "deps": [":aidl-interface-import-latest"],
        "version": "2",
    },
        {
        "deps": [":aidl-interface-import-latest"],
        "version": "3",
    },
    ]`,
			}),
		},
	})
}

func TestAidlInterfaceWithVersionedImport(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with single versioned aidl_interface import`,
		Blueprint: `
			aidl_interface {
				name: "aidl-interface-import",
				versions: [
					"1",
					"2",
				],
			}
			aidl_interface {
				name: "aidl-interface1",
				imports: [
					"aidl-interface-import-V2",
				],
				versions: [
					"1",
					"2",
					"3",
				],
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface-import", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"versions_with_info": `[
        {
        "version": "1",
    },
        {
        "version": "2",
    },
    ]`,
			}),
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"java_config": `{
        "enabled": True,
    }`,
				"cpp_config": `{
        "enabled": True,
    }`,
				"ndk_config": `{
        "enabled": True,
    }`,
				"versions_with_info": `[
        {
        "deps": [":aidl-interface-import-V2"],
        "version": "1",
    },
        {
        "deps": [":aidl-interface-import-V2"],
        "version": "2",
    },
        {
        "deps": [":aidl-interface-import-V2"],
        "version": "3",
    },
    ]`,
			}),
		},
	})
}

func TestAidlInterfaceWithCppAndNdkConfigs(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with cpp and ndk configs`,
		Blueprint: `
			aidl_interface {
				name: "foo",
                backend: {
                    java: {
                        enabled: false,
                    },
                    cpp: {
                        min_sdk_version: "2",
                    },
                    ndk: {
                        min_sdk_version: "1",
                    },
                }
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "foo", bp2build.AttrNameToString{
				"cpp_config": `{
        "enabled": True,
        "min_sdk_version": "2",
    }`,
				"ndk_config": `{
        "enabled": True,
        "min_sdk_version": "1",
    }`,
			}),
		},
	})
}

func TestAidlInterfaceWithUnstablePropSet(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with unstable prop set`,
		Blueprint: `
			aidl_interface {
				name: "foo",
				unstable: true,
                backend: {
                    java: {
                        enabled: false,
                    },
                    cpp: {
                        enabled: false,
                    },
                }
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "foo", bp2build.AttrNameToString{
				"unstable": "True",
				"ndk_config": `{
        "enabled": True,
    }`,
			}),
		},
	})
}

func TestAidlInterfaceWithFrozenPropSet(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface with frozen prop set`,
		Blueprint: `
			aidl_interface {
				name: "foo",
				frozen: true,
				versions: ["1"],
                backend: {
                    java: {
                        enabled: false,
                    },
                    cpp: {
                        enabled: false,
                    },
                }
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "foo", bp2build.AttrNameToString{
				"frozen": "True",
				"versions_with_info": `[{
        "version": "1",
    }]`,
				"ndk_config": `{
        "enabled": True,
    }`,
			}),
		},
	})
}

func TestAidlInterfaceWithApexAvailable(t *testing.T) {
	runAidlInterfaceTestCase(t, bp2build.Bp2buildTestCase{
		Description: `aidl_interface apex_available`,
		Blueprint: `
			aidl_interface {
				name: "aidl-interface1",
                backend: {
                    java: {
                        enabled: false,
                    },
                    cpp: {
                        enabled: false,
                    },
                    ndk: {
                        enabled: true,
                        apex_available: [
                            "com.android.abd",
                            "//apex_available:platform",
                        ],
                    },
                }
			}`,
		ExpectedBazelTargets: []string{
			bp2build.MakeBazelTargetNoRestrictions("aidl_interface", "aidl-interface1", bp2build.AttrNameToString{
				"ndk_config": `{
        "enabled": True,
        "tags": [
            "apex_available=com.android.abd",
            "apex_available=//apex_available:platform",
        ],
    }`,
			}),
		},
	})
}
