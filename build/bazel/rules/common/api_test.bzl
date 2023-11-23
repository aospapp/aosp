load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//build/bazel/rules/common:api.bzl", "api")

def _api_levels_test_impl(ctx):
    env = unittest.begin(ctx)

    # schema: version string to parse: (expected api int, is preview api)
    _LEVELS_UNDER_TEST = {
        # numbers
        "9": (9, False),  # earliest released number
        "21": (21, False),
        "30": (30, False),
        "33": (33, False),
        # unchecked non final api level (not finalized, not preview, not current)
        "1234": (1234, False),
        "8999": (8999, False),
        "9999": (9999, False),
        "10001": (10001, False),
        # letters
        "G": (9, False),  # earliest released letter
        "J-MR1": (17, False),
        "R": (30, False),
        "S": (31, False),
        "S-V2": (32, False),
        # codenames
        "Tiramisu": (33, False),
        "UpsideDownCake": (9000, True),  # preview
        "current": (10000, True),  # future (considered as preview)
        # preview numbers
        "9000": (9000, True),  # preview
        "10000": (10000, True),  # future (considered as preview)
    }

    for level, expected in _LEVELS_UNDER_TEST.items():
        asserts.equals(env, expected[0], api.parse_api_level_from_version(level), "unexpected api level parsed for %s" % level)
        asserts.equals(env, expected[1], api.is_preview(level), "unexpected is_preview value for %s" % level)

    return unittest.end(env)

api_levels_test = unittest.make(_api_levels_test_impl)

def _final_or_future_test_impl(ctx):
    env = unittest.begin(ctx)

    # schema: version string to parse: expected api int
    _LEVELS_UNDER_TEST = {
        # finalized
        "30": 30,
        "33": 33,
        "S": 31,
        "S-V2": 32,
        "Tiramisu": 33,
        # not finalized
        "UpsideDownCake": 10000,
        "current": 10000,
        "9000": 10000,
        "10000": 10000,
    }

    for level, expected in _LEVELS_UNDER_TEST.items():
        asserts.equals(
            env,
            expected,
            api.final_or_future(api.parse_api_level_from_version(level)),
            "unexpected final or future api for %s" % level,
        )

    return unittest.end(env)

final_or_future_test = unittest.make(_final_or_future_test_impl)

def api_levels_test_suite(name):
    unittest.suite(
        name,
        api_levels_test,
        final_or_future_test,
    )
