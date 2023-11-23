# Compiler wrapper

See the comments on the top of main.go.
Build is split into 2 steps via separate commands:
- bundle: copies the sources and the `build.py` file into
  a folder.
- build: builds the actual go binary, assuming it is executed
  from the folder created by `bundle.py`.

This allows to copy the sources to a ChromeOS / Android
package, including the build script, and then
build from there without a dependency on toolchain-utils
itself.

## Testing Inside the Chroot

To test updates to the wrapper locally:

Run `install_compiler_wrapper.sh` to install the new wrapper in the chroot:
```
(chroot) ~/trunk/src/third_party/toolchain-utils/compiler_wrapper/install_compiler_wrapper.sh
```

Then perform the tests, e.g. build with the new compiler.


## Updating the Wrapper for ChromeOS

To update the wrapper for everyone, the new wrapper configuration must be copied
into chromiumos-overlay, and new revisions of the gcc and llvm ebuilds must be
created.

Copy over sources and `build.py` to chromiumos-overlay:
```
(chroot) /mnt/host/source/src/third_party/chromiumos-overlay/sys-devel/llvm/files/update_compiler_wrapper.sh
```

Rename chromiumos-overlay/sys-devel/llvm/llvm-${VERSION}.ebuild to the next
revision number. For example, if the current version is
11.0_pre394483_p20200618-r2:
```
(chroot) cd ~/trunk/src/third_party/chromiumos-overlay
(chroot) git mv llvm-11.0_pre394483_p20200618-r2.ebuild llvm-11.0_pre394483_p20200618-r3.ebuild
```

Rename chromiumos-overlay/sys-devel/gcc/gcc-${VERSION}.ebuild to the next
revision number.  For example, if the current version is 10.2.0-r3:
```
(chroot) cd ~/trunk/src/third_party/chromiumos-overlay
(chroot) git mv sys-devel/gcc/gcc-10.2.0-r3.ebuild sys-devel/gcc/gcc-10.2.0-r4.ebuild
```

Commit those changes together with the changes made by
`update_compiler_wrapper.sh`.

The changes can then be reviewed and submitted through the normal process.


## Paths

`build.py` is called by these ebuilds:

- third_party/chromiumos-overlay/sys-devel/llvm/llvm-*.ebuild
- third_party/chromiumos-overlay/sys-devel/gcc/gcc-*.ebuild

Generated wrappers are stored here:

- Sysroot wrapper with ccache:
  `/usr/x86_64-pc-linux-gnu/<arch>/gcc-bin/10.2.0/sysroot_wrapper.hardened.ccache`
- Sysroot wrapper without ccache:
  `/usr/x86_64-pc-linux-gnu/<arch>/gcc-bin/10.2.0/sysroot_wrapper.hardened.noccache`
- Clang host wrapper:
  `/usr/bin/clang_host_wrapper`
- Gcc host wrapper:
  `/usr/x86_64-pc-linux-gnu/gcc-bin/10.2.0/host_wrapper`

## Using the compiler wrapper to crash arbitrary compilations

When Clang crashes, its output can be extremely useful. Often, it will provide
the user with a stack trace, and messages like:

```
clang-15: unable to execute command: Illegal instruction
clang-15: note: diagnostic msg: /tmp/clang_crash_diagnostics/foo-5420d2.c
clang-15: note: diagnostic msg: /tmp/clang_crash_diagnostics/foo-5420d2.sh
```

Where the artifacts at `/tmp/clang_crash_diagnostics/foo-*` are a full,
self-contained reproducer of the inputs that caused the crash in question.
Often, such a reproducer is very valuable to have even for cases where a crash
_doesn't_ happen (e.g., maybe Clang is now emitting an error where it used to
not do so, and we want to bisect upstream LLVM with that info). Normally,
collecting and crafting such a reproducer is a multi-step process, and can be
error-prone; compile commands may rely on env vars, they may be done within
`chroot`s, they may rely on being executed in a particular directory, they may
rely on intermediate state, etc.

Because of the usefulness of these crash reports, our wrapper supports crashing
Clang even on files that ordinarily don't cause Clang to crash. For various
reasons (b/236736327), this support currently requires rebuilding and
redeploying the wrapper in order to work. That said, this could be a valuable
tool for devs interested in creating a self-contained reproducer without having
to manually reproduce the environment in which a particular build was performed.
