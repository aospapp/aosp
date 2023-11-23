//
//  Copyright 2021 Google, Inc.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

extern crate protobuf_codegen;

use std::env;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

fn paths_to_strs<P: AsRef<Path>>(paths: &[P]) -> Vec<&str> {
    paths.iter().map(|p| p.as_ref().as_os_str().to_str().unwrap()).collect()
}

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());

    // Proto root is //tools/netsim/src/proto
    let proto_root = match env::var("PLATFORM_SUBDIR") {
        Ok(dir) => PathBuf::from(dir).join("tools/netsim"),
        // Currently at //tools/netsim/rust/backend-proto
        Err(_) => PathBuf::from(env::current_dir().unwrap()).join("../..").canonicalize().unwrap(),
    };

    // Generate protobuf output
    let proto_dir = proto_root.join("src/proto");
    let proto_input_files = [
        proto_dir.join("model.proto"),
        proto_dir.join("frontend.proto"),
        proto_dir.join("common.proto"),
    ];
    let proto_include_dirs = [proto_dir.clone()];
    let proto_out_dir = proto_root.join("rust/frontend-proto/src");

    protobuf_codegen::Codegen::new()
        .out_dir(proto_out_dir.as_os_str().to_str().unwrap())
        .inputs(&paths_to_strs(&proto_input_files))
        .includes(&paths_to_strs(&proto_include_dirs))
        .run()
        .expect("protoc");
}
