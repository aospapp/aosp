use std::process::Command;

/// Gets the path of the `hwtrust` binary that works with `atest` and `Cargo`.
fn hwtrust_bin() -> &'static str {
    option_env!("CARGO_BIN_EXE_hwtrust").unwrap_or("./hwtrust")
}

#[test]
fn exit_code_for_good_chain() {
    let output = Command::new(hwtrust_bin())
        .args(["verify-dice-chain", "testdata/dice/valid_ed25519.chain"])
        .output()
        .unwrap();
    assert!(output.status.success());
}

#[test]
fn exit_code_for_bad_chain() {
    let output = Command::new(hwtrust_bin())
        .args(["verify-dice-chain", "testdata/dice/bad_p256.chain"])
        .output()
        .unwrap();
    assert!(!output.status.success());
}
