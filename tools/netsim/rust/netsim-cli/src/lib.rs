// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Command Line Interface for Netsim

mod args;
mod browser;
mod pcap_handler;
mod requests;
mod response;

use std::env;
use std::fs::File;
use std::path::PathBuf;

use args::{BinaryProtobuf, GetCapture, NetsimArgs};
use clap::Parser;
use cxx::UniquePtr;
use frontend_client_cxx::ffi::{new_frontend_client, ClientResult, FrontendClient, GrpcMethod};
use frontend_client_cxx::ClientResponseReader;
use pcap_handler::CaptureHandler;

// helper function to process streaming Grpc request
fn perform_streaming_request(
    client: &cxx::UniquePtr<FrontendClient>,
    cmd: &GetCapture,
    req: &BinaryProtobuf,
    filename: &str,
) -> UniquePtr<ClientResult> {
    let dir = if cmd.location.is_some() {
        PathBuf::from(cmd.location.to_owned().unwrap())
    } else {
        env::current_dir().unwrap()
    };
    // Find next available file name
    let mut output_file = dir.join(filename.to_string() + ".pcap");
    let mut idx = 0;
    while output_file.exists() {
        idx += 1;
        output_file = dir.join(format!("{}_{}.pcap", filename, idx));
    }
    client.get_capture(
        req,
        &ClientResponseReader {
            handler: Box::new(CaptureHandler {
                file: File::create(&output_file).unwrap_or_else(|_| {
                    panic!("Failed to create file: {}", &output_file.display())
                }),
                path: output_file,
            }),
        },
    )
}

/// helper function to send the Grpc request(s) and handle the response(s) per the given command
fn perform_command(
    command: &mut args::Command,
    client: cxx::UniquePtr<FrontendClient>,
    grpc_method: GrpcMethod,
    verbose: bool,
) -> Result<(), String> {
    // Get command's gRPC request(s)
    let requests = match command {
        args::Command::Pcap(args::Pcap::Patch(_) | args::Pcap::Get(_)) => {
            command.get_requests(&client)
        }
        _ => vec![command.get_request_bytes()],
    };

    // Process each request
    for (i, req) in requests.iter().enumerate() {
        let result = match command {
            // Continuous option sends the gRPC call every second
            args::Command::Devices(ref cmd) if cmd.continuous => loop {
                process_result(command, client.send_grpc(&grpc_method, req), verbose)?;
                std::thread::sleep(std::time::Duration::from_secs(1));
            },
            // Get Pcap use streaming gRPC reader request
            args::Command::Pcap(args::Pcap::Get(ref cmd)) => {
                perform_streaming_request(&client, cmd, req, &cmd.filenames[i])
            }
            // All other commands use a single gRPC call
            _ => client.send_grpc(&grpc_method, req),
        };
        process_result(command, result, verbose)?;
    }
    Ok(())
}

/// Check and handle the gRPC call result
fn process_result(
    command: &args::Command,
    result: UniquePtr<ClientResult>,
    verbose: bool,
) -> Result<(), String> {
    if result.is_ok() {
        command.print_response(result.byte_vec().as_slice(), verbose);
    } else {
        return Err(format!("Grpc call error: {}", result.err()));
    }
    Ok(())
}
#[no_mangle]
/// main Rust netsim CLI function to be called by C wrapper netsim.cc
pub extern "C" fn rust_main() {
    let mut args = NetsimArgs::parse();
    if matches!(args.command, args::Command::Gui) {
        browser::open("http://localhost:7681/");
        return;
    }
    let grpc_method = args.command.grpc_method();
    let client = new_frontend_client();
    if client.is_null() {
        eprintln!("Unable to create frontend client. Please ensure netsimd is running.");
        return;
    }
    if let Err(e) = perform_command(&mut args.command, client, grpc_method, args.verbose) {
        eprintln!("{e}");
    }
}
