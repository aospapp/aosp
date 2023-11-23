# Bazel rules for API export
This package contains Bazel rules for declaring API contributions of API
domains to API surfaces (go/android-build-api-domains)

## WARNING:
API export is expected to run in **Standalone Bazel mode**
(go/multi-tree-api-export). As such, rules defined in this package should not
have any dependencies on bp2build (most notably the generated `@soong_injection`
workspace)
