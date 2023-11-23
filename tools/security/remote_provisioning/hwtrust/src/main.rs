//! A tool for handling data related to the hardware root-of-trust.

use anyhow::Result;
use clap::{Parser, Subcommand, ValueEnum};
use hwtrust::dice;
use hwtrust::session::{Options, Session};
use std::fs;

#[derive(Parser)]
/// A tool for handling data related to the hardware root-of-trust
#[clap(name = "hwtrust")]
struct Args {
    #[clap(subcommand)]
    action: Action,
}

#[derive(Subcommand)]
enum Action {
    VerifyDiceChain(VerifyDiceChainArgs),
}

#[derive(Parser)]
/// Verify that a DICE chain is well-formed
///
/// DICE chains are expected to follow the specification of the RKP HAL [1] which is based on the
/// Open Profile for DICE [2].
///
/// [1] -- https://cs.android.com/android/platform/superproject/+/master:hardware/interfaces/security/rkp/aidl/android/hardware/security/keymint/IRemotelyProvisionedComponent.aidl
/// [2] -- https://pigweed.googlesource.com/open-dice/+/refs/heads/main/docs/specification.md
struct VerifyDiceChainArgs {
    /// Dump the DICE chain on the standard output
    #[clap(long)]
    dump: bool,

    /// Path to a file containing a DICE chain
    chain: String,

    /// The VSR version to validate against. If omitted, the set of rules that are used have no
    /// compromises or workarounds and new implementations should validate against them as it will
    /// be the basis for future VSR versions.
    #[clap(long, value_enum)]
    vsr: Option<VsrVersion>,
}

#[derive(Copy, Clone, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
enum VsrVersion {
    /// VSR 13 / Android T / 2022
    Vsr13,
    /// VSR 14 / Android U / 2023
    Vsr14,
}

fn main() -> Result<()> {
    let args = Args::parse();
    let Action::VerifyDiceChain(sub_args) = args.action;
    let session = Session {
        options: match sub_args.vsr {
            Some(VsrVersion::Vsr13) => Options::vsr13(),
            Some(VsrVersion::Vsr14) => Options::vsr14(),
            None => Options::default(),
        },
    };
    let chain = dice::Chain::from_cbor(&session, &fs::read(sub_args.chain)?)?;
    println!("Success!");
    if sub_args.dump {
        print!("{}", chain);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn verify_command() {
        Args::command().debug_assert();
    }
}
