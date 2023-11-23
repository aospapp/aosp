//! Build file to generate packets
//!
//! Run `cargo install .` in `tools/pdl` to ensure `pdl` is in your
//! path.
use std::{
    env,
    fs::File,
    path::Path,
    process::{Command, Stdio},
};

fn main() {
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("_packets.rs");
    let dest_file = File::create(dest_path).unwrap();

    let pdl = Command::new("pdl")
        .args(["--output-format", "rust_no_alloc", "src/packets.pdl"])
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();

    let mut rustfmt =
        Command::new("rustfmt").stdin(pdl.stdout.unwrap()).stdout(dest_file).spawn().unwrap();

    rustfmt.wait().unwrap();

    if let Some(err) = rustfmt.stderr {
        panic!("{err:?}");
    }

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/packets.pdl");
}
