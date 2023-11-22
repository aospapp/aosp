#!/usr/bin/env python

import os
import subprocess
import sys
from antlr4 import *
from gnparser.gnLexer import gnLexer
from gnparser.gnParser import gnParser
from gnparser.gnListener import gnListener
from string import Template

DBG = False

# Reformat the specified Android.bp file
def _bpFmt(filename):
  ## NOTE: bpfmt does not set error code even when the bp file is illegal.
  print subprocess.check_output(["bpfmt", "-w", filename])

def _bpList(entries):
  return '[' + ",".join(['"' + x + '"' for x in entries]) + ']'

# Write an Android.bp in the simpler format used by v8_libplatform and
# v8_libsampler
def _writeBP(filename, module_name, sources):
  if not sources:
    raise ValueError('No sources for ' + filename)

  with open(filename, 'w') as out:
    out.write(Template('''
      // GENERATED, do not edit
      // for changes, see genmakefiles.py
      cc_library_static {
          name: "$module_name",
          defaults: ["v8_defaults"],
          srcs: $srcs,
          local_include_dirs: ["src", "include"],
      }
    ''').substitute({'module_name': module_name, 'srcs' : _bpList(sorted(sources))}))

  _bpFmt(filename)


def _writeV8SrcBP(getSourcesFunc):
  sources = getSourcesFunc(None)
  if not sources:
    raise ValueError('Must specify v8_base target properties')
  sources.add('src/setup-isolate-full.cc')
    # sources.add('src/builtins/setup-builtins-internal.cc')
    # sources.add('src/interpreter/setup-interpreter-internal.cc')
  arm_src = list(getSourcesFunc('arm') - sources)
  arm64_src = list(getSourcesFunc('arm64') - sources)
  x86_src = list(getSourcesFunc('x86') - sources)
  x86_64_src = list(getSourcesFunc('x64') - sources)
  mips_src = list(getSourcesFunc('mips') - sources)
  mips64_src = list(getSourcesFunc('mips64') - sources)

  filename = 'Android.v8.bp'
  with open(filename, 'w') as out:
    out.write(Template('''
      // GENERATED, do not edit
      // for changes, see genmakefiles.py
      cc_library_static {
          name: "libv8src",
          defaults: ["v8_defaults"],
          srcs: $srcs,
          arch: {
              arm: {
                 srcs: $arm_src,
              },
              arm64: {
                 srcs: $arm64_src,
              },
              mips: {
                 srcs: $mips_src,
              },
              mips64: {
                 srcs: $mips64_src,
              },
              x86: {
                 srcs: $x86_src,
              },
              x86_64: {
                 srcs: $x86_64_src,
              },
          },
          target: {
              android: {
                  cflags: ["-DANDROID_LINK_SHARED_ICU4C"],
              },
          },
          local_include_dirs: ["src"],
          header_libs: ["libicuuc_headers", "libicui18n_headers"],
          generated_headers: ["v8_torque_file"],
          generated_sources: ["v8_torque_file_cc"],
      }
    ''').substitute({'srcs': _bpList(sorted(sources)),
                     'arm_src': _bpList(sorted(arm_src)),
                     'arm64_src': _bpList(sorted(arm64_src)),
                     'mips_src': _bpList(sorted(mips_src)),
                     'mips64_src': _bpList(sorted(mips64_src)),
                     'x86_src': _bpList(sorted(x86_src)),
                     'x86_64_src': _bpList(sorted(x86_64_src)),
                    }))

  _bpFmt(filename)

def _writeGeneratedFilesBP(sources):
  if not sources:
    raise ValueError('Must specify j2sc target properties')

  filename = 'Android.v8gen.bp'
  with open(filename, 'w') as out:
    out.write(Template('''
      // GENERATED, do not edit
      // for changes, see genmakefiles.py
      filegroup {
          name: "v8_js_lib_files",
          srcs: $srcs,
      }
    ''').substitute({'srcs' : _bpList(sources)})) ## Not sorted intentionally

  _bpFmt(filename)

def _writeLibBaseBP(sources):
  if not sources:
    raise ValueError('Must specify v8_libbase target properties')

  filename = 'Android.base.bp'
  with open(filename, 'w') as out:
    out.write(Template('''
      // GENERATED, do not edit
      // for changes, see genmakefiles.py
      cc_library_static {
          name: "libv8base",
          defaults: ["v8_defaults"],
          host_supported: true,
          srcs: $srcs,
          local_include_dirs: ["src"],
          target: {
              android: {
                  srcs: ["src/base/debug/stack_trace_android.cc"],
              },
              linux: {
                  srcs: ["src/base/platform/platform-linux.cc"],
              },
              host: {
                  srcs: ["src/base/debug/stack_trace_posix.cc"],
                  cflags: ["-UANDROID"],
              },
              darwin: {
                  srcs: ["src/base/platform/platform-macos.cc"],
              },
          },
      }
    ''').substitute({'srcs' : _bpList(sorted(sources))}))

  _bpFmt(filename)


def _expr_to_str(expr):
  val = expr.unaryexpr().primaryexpr()
  if val.String():
    return val.String().getText()[1:-1] ## Strip quotation marks around string
  elif val.Identifier():
    return val.Identifier().getText()
  else:
    if DBG: print 'WARN: unhandled primary expression'
    return None

class V8GnListener(gnListener):
    def __init__(self, target, arch, only_cc_files):
        super(gnListener, self).__init__()
        self._match = False
        self._depth = 0
        self._target = target
        self._arch = arch
        self._sources = []
        self._fixed_conditions = {
            'use_jumbo_build' : True,
            'use_jumbo_build==true' : True,
            'is_win' : False,
            'is_linux' : False,
            'v8_postmortem_support' : False,
            'v8_enable_i18n_support': True,
            '!v8_enable_i18n_support': False,
            'current_os!="aix"' : True,
            'is_posix||is_fuchsia' : True,
            'v8_current_cpu=="arm"' : arch == 'arm',
            'v8_current_cpu=="arm64"' : arch == 'arm64',
            'v8_current_cpu=="x86"' : arch == 'x86',
            'v8_current_cpu=="x64"' : arch == 'x64',
            'v8_current_cpu=="mips"||v8_current_cpu=="mipsel"' : arch == 'mips',
            'v8_current_cpu=="mips64"||v8_current_cpu=="mips64el"' : arch == 'mips64',
            'v8_current_cpu=="ppc"||v8_current_cpu=="ppc64"' : False,
            'v8_current_cpu=="s390"||v8_current_cpu=="s390x"' : False,

        }
        self._only_cc_files = only_cc_files

    def _match_call_target(self, ctx):
      call_type = ctx.Identifier().getText()
      if not call_type in ['v8_source_set', 'v8_component', 'action']: return False
      call_name = _expr_to_str(ctx.exprlist().expr(0))
      return call_name == self._target

    def enterCall(self, ctx):
      if self._depth == 1 and self._match_call_target(ctx):
        self._match = True
        self._conditions = [] ## [(value, condition), ...]
        if DBG: print 'Found call', str(ctx.Identifier()), ctx.exprlist().getText()

    def exitCall(self, ctx):
      if self._match and self._match_call_target(ctx):
        self._match = False
        self._conditions = []
        if DBG: print 'Left call'

    def _extract_sources(self, ctx):
      op = ctx.AssignOp().getText()
      if not ctx.expr().unaryexpr().primaryexpr().exprlist():
        ## sources += check_header_includes_sources
        return
      srcs = map(_expr_to_str, ctx.expr().unaryexpr().primaryexpr().exprlist().expr())
      if self._only_cc_files:
        srcs = [x for x in srcs if x.endswith('.cc')]
      if DBG: print '_extract_sources: ', len(srcs), "condition:", self._conditions
      if op == '=':
        if self._sources:
          print "WARN: override sources"
        self._sources = srcs
      elif op == '+=':
        self._sources.extend(srcs)

    def _compute_condition(self, ctx):
      condition = ctx.expr().getText()
      if DBG: print '_extract_condition', condition
      if condition in self._fixed_conditions:
        result = self._fixed_conditions[condition]
      else:
        print 'WARN: unknown condition, assume False', condition
        self._fixed_conditions[condition] = False
        result = False
      if DBG: print 'Add condition:', condition
      self._conditions.append((result, condition))


    def enterCondition(self, ctx):
      if not self._match: return
      self._compute_condition(ctx)

    def enterElsec(self, ctx):
      if not self._match: return
      c = self._conditions[-1]
      self._conditions[-1] = (not c[0], c[1])
      if DBG: print 'Negate condition:', self._conditions[-1]

    def exitCondition(self, ctx):
      if not self._match: return
      if DBG: print 'Remove conditions: ', self._conditions[-1]
      del self._conditions[-1]

    def _flatten_conditions(self):
      if DBG: print '_flatten_conditions: ', self._conditions
      for condition, _ in self._conditions:
        if not condition:
          return False
      return True

    def enterAssignment(self, ctx):
      if not self._match: return
      if ctx.lvalue().Identifier().getText() == "sources":
        if self._flatten_conditions():
          self._extract_sources(ctx)

    def enterStatement(self, ctx):
      self._depth += 1

    def exitStatement(self, ctx):
      self._depth -= 1

    def get_sources(self):
      seen = set()
      result = []
      ## Deduplicate list while maintaining ordering. needed for js2c files
      for s in self._sources:
        if not s in seen:
          result.append(s)
          seen.add(s)
      return result

def parseSources(tree, target, arch = None, only_cc_files = True):
  listener = V8GnListener(target, arch, only_cc_files)
  ParseTreeWalker().walk(listener, tree)
  return listener.get_sources()

def GenerateMakefiles():
    f = FileStream(os.path.join(os.getcwd(), './BUILD.gn'))
    lexer = gnLexer(f)
    stream = CommonTokenStream(lexer)
    parser = gnParser(stream)
    tree = parser.r()

    _writeBP('Android.platform.bp', 'libv8platform', parseSources(tree, "v8_libplatform"))
    _writeBP('Android.sampler.bp', 'libv8sampler', parseSources(tree, "v8_libsampler"))
    _writeV8SrcBP(lambda arch: set(parseSources(tree, "v8_base", arch) + parseSources(tree, "v8_initializers", arch)))
    _writeGeneratedFilesBP(parseSources(tree, "js2c", None, False))
    _writeLibBaseBP(parseSources(tree, "v8_libbase"))

if __name__ == '__main__':
  GenerateMakefiles()
