load(":android_product.bzl", "all_android_product_labels")

package(default_visibility = ["//visibility:public"])

# The current product we're building for. We could've chosen this product via a --platforms flag,
# or transitioned into it. This is used to select() on the current product in the product_vars
# target below. Note that this represents the current android _product_ as defined in pre-bazel
# terms. There are several bazel platforms that can resolve to the same product, for example the
# host platform and the device platform.
constraint_setting(name = "current_product")

# This target can be used to read product variables that aren't represented by other constraint
# values (like the arch is).
#
# You may ask why every product variable isn't represented as a constraint setting. There are a
# couple reasons for this:
#  - Some variables represent files to use in rule implementations, like the default app
#    certificate. Constraint settings would not allow adding a dependency on another file.
#  - Some variables have more complicated data than a simple enum, like TidyChecks which is a list
#    of strings. While this may be possible to extract from the name of a constraint value, it
#    would be awkward.
#  - Not all configuration variables need to be used in toolchain resolution.
#
# This target has 2 providers:
#  - TemplateVariableInfo: this is a native platform_common.TemplateVariableInfo provider that
#    contains a subset of the product variables to be available for expansion using make-like syntax
#    on certain rule attributes. Soong had this feature, so it was ported to bazel.
#  - ProductVariablesInfo: A custom provider that is supposed to replace direct accesses of
#    soong.variables. You'd use this rule instead of reading from soong.variables directly so that
#    when you transition into another product, you get the updated product config variables.
#
# This method of accessing product variables can't be used from macros / the bazel loading phase.
# This is intentional, because if you could read them during the loading phase, you wouldn't be
# able to transition into new values. Instead, please defer accesses to the analysis phase.
#
# All android_product()s must register their label here.
alias(
    name = "product_vars",
    actual = select({
        label + "_constraint_value": label + "_product_vars"
        for label in all_android_product_labels
    }),
)
