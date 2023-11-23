# Copyright 2022 Google LLC. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the License);
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Some utils"""

load("//:visibility.bzl", "RULES_KOTLIN")

# Mark targets that's aren't expected to build, but are needed for analysis test assertions.
ONLY_FOR_ANALYSIS_TEST_TAGS = ["manual", "nobuilder", "only_for_analysis_test"]

def create_file(name, content):
    if content.startswith("\n"):
        content = content[1:-1]

    native.genrule(
        name = "gen_" + name,
        outs = [name],
        cmd = """
cat > $@ <<EOF
%s
EOF
""" % content,
    )

    return name

def _create_dir_impl(ctx):
    dir = ctx.actions.declare_directory(ctx.attr.name)

    command = "mkdir -p {0} " + ("&& cp {1} {0}" if ctx.files.srcs else "# {1}")
    ctx.actions.run_shell(
        command = command.format(
            dir.path + "/" + ctx.attr.subdir,
            " ".join([s.path for s in ctx.files.srcs]),
        ),
        inputs = ctx.files.srcs,
        outputs = [dir],
    )

    return [DefaultInfo(files = depset([dir]))]

_create_dir = rule(
    implementation = _create_dir_impl,
    attrs = dict(
        subdir = attr.string(),
        srcs = attr.label_list(allow_files = True),
    ),
)

def create_dir(
        name,
        subdir = None,
        srcs = None):
    _create_dir(
        name = name,
        subdir = subdir,
        srcs = srcs,
    )
    return name

def get_action(actions, mnemonic):
    """Get a specific action

    Args:
      actions: [List[Action]]
      mnemonic: [string] Identify the action whose args to search

    Returns:
      [Optional[action]] The arg value, or None if it couldn't be found
    """
    menmonic_actions = [a for a in actions if a.mnemonic == mnemonic]
    if len(menmonic_actions) == 0:
        return None
    elif len(menmonic_actions) > 1:
        fail("Expected a single '%s' action" % mnemonic)

    return menmonic_actions[0]

def get_arg(action, arg_name):
    """Get a named arg from a specific action

    Args:
      action: [Optional[Action]]
      arg_name: [string]

    Returns:
      [Optional[string]] The arg value, or None if it couldn't be found
    """
    if not action:
        return None

    arg_values = [a for a in action.argv if a.startswith(arg_name)]
    if len(arg_values) == 0:
        return None
    elif len(arg_values) > 1:
        fail("Expected a single '%s' arg" % arg_name)

    return arg_values[0][len(arg_name):]
