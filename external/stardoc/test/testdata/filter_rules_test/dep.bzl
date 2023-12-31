# buildifier: disable=module-docstring
def my_rule_impl(ctx):
    _ignore = [ctx]  # @unused
    return []

my_rule = rule(
    implementation = my_rule_impl,
    doc = "This is the dep rule. It does stuff.",
    attrs = {
        "first": attr.label(
            mandatory = True,
            doc = "dep's my_rule doc string",
            allow_single_file = True,
        ),
        "second": attr.string_dict(mandatory = True),
    },
)
