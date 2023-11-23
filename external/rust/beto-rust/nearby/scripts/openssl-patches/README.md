This directory contains patch files for `rust-openssl` for it to build successfully with
`--features=unstable_boringssl`.

After running `prepare-boringssl.sh`, the `rust-openssl` git repo is cloned to
`beto-rust/boringssl-build/rust-openssl/openssl`, and the patches in this directory will be applied.

If you make further changes, or update the "base commit" in `prepare-boringssl.sh`, you can
regenerate the patch files by checking out to the desired state of the tree, with all changes
committed, and run `git format-patch BASE_COMMIT`. (Note: `BASE_COMMIT` is set by
`prepare-boringssl.sh`)
