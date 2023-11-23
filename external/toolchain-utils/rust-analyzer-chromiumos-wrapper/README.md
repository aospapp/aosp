# rust-analyzer-chromiumos-wrapper

## Intro

rust-analyzer is an LSP server for the Rust language. It allows editors like
vim, emacs, or VS Code to provide IDE-like features for Rust.

This program, `rust-analyzer-chromiumos-wrapper`, is a wrapper around
`rust-analyzer`. It exists to translate paths between an instance of
rust-analyzer running inside the chromiumos chroot and a client running outside
the chroot.

It is of course possible to simply run `rust-analyzer` outside the chroot, but
version mismatch issues may lead to a suboptimal experience.

It should run outside the chroot. If invoked in a `chromiumos` repo in a
subdirectory of either `chromiumos/src` or `chromiumos/chroot`, it will attempt
to invoke `rust-analyzer` inside the chroot and translate paths. Otherwise, it
will attempt to invoke a `rust-analyzer` outside the chroot and will not
translate paths.

It supports none of rust-analyzer's command line options, which aren't
necessary for acting as a LSP server anyway.

## Quickstart

*Outside* the chroot, install the `rust-analyzer-chromiumos-wrapper` binary:

```
cargo install --path /path-to-a-chromiumos-checkout/src/third_party/toolchain-utils/rust-analyzer-chromiumos-wrapper
```

Make sure `~/.cargo/bin' is in your PATH, or move/symlink `~/.cargo/bin/rust-analyzer-chromiumos-wrapper` to a location in your PATH.

Configure your editor to use the binary `rust-analyzer-chromiumos-wrapper` as
`rust-analyzer`. In Neovim, if you're using
[nvim-lspconfig](https://github.com/neovim/nvim-lspconfig), this can be done by
putting the following in your `init.lua`:

```
require('lspconfig')['rust_analyzer'].setup {
  cmd = {'rust-analyzer-chromiumos-wrapper'},
}
```

This configuration is specific to your editor, but see the
[Rust analyzer manual](https://rust-analyzer.github.io/manual.html) for
more about several different editors.

Once the above general configuration is set up, you'll need to install
`rust-analyzer` inside each chroot where you want to edit code:
```
sudo emerge rust-analyzer
```

## Misc

A wrapper isn't necessary for clangd, because clangd supports the option
`--path-mappings` to translate paths. In principle a similar option could be
added to `rust-analyzer`, obviating the need for this wrapper. See this
[issue on github](https://github.com/rust-lang/rust-analyzer/issues/12485).
