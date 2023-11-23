// Copyright 2023 Google LLC
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

//! Netsim cxx libraries.

#![allow(dead_code)]

mod captures;
mod devices;
mod http_server;
mod ranging;
mod transport;
mod uwb;
mod version;

use std::pin::Pin;

use cxx::let_cxx_string;
use ffi::CxxServerResponseWriter;
use http_server::http_request::StrHeaders;
use http_server::server_response::ServerResponseWritable;

use crate::transport::fd::handle_response;
use crate::transport::fd::run_fd_transport;

use crate::captures::handlers::{
    clear_pcap_files, handle_capture_cxx, handle_packet_request, handle_packet_response,
};
use crate::http_server::run_http_server;
use crate::ranging::*;
use crate::uwb::facade::*;
use crate::version::*;

#[cxx::bridge(namespace = "netsim")]
mod ffi {

    extern "Rust" {

        #[cxx_name = "RunFdTransport"]
        fn run_fd_transport(startup_json: &String);

        #[cxx_name = "RunHttpServer"]
        fn run_http_server();

        // Ranging

        #[cxx_name = "DistanceToRssi"]
        fn distance_to_rssi(tx_power: i8, distance: f32) -> i8;

        // Version

        #[cxx_name = "GetVersion"]
        fn get_version() -> String;

        // handle_capture_cxx translates each argument into an appropriate Rust type

        #[cxx_name = "HandleCaptureCxx"]
        fn handle_capture_cxx(
            responder: Pin<&mut CxxServerResponseWriter>,
            method: String,
            param: String,
            body: String,
        );

        // Packet hub

        #[cxx_name = HandleResponse]
        #[namespace = "netsim::fd"]
        fn handle_response(kind: u32, facade_id: u32, packet: &CxxVector<u8>, packet_type: u8);

        // Capture Resource

        #[cxx_name = HandleRequest]
        #[namespace = "netsim::pcap"]
        fn handle_packet_request(
            kind: u32,
            facade_id: u32,
            packet: &CxxVector<u8>,
            packet_type: u32,
        );

        #[cxx_name = HandleResponse]
        #[namespace = "netsim::pcap"]
        fn handle_packet_response(
            kind: u32,
            facade_id: u32,
            packet: &CxxVector<u8>,
            packet_type: u32,
        );

        // Clearing out all pcap Files in temp directory

        #[cxx_name = ClearPcapFiles]
        #[namespace = "netsim::pcap"]
        fn clear_pcap_files() -> bool;

        // Uwb Facade.

        #[cxx_name = HandleUwbRequestCxx]
        #[namespace = "netsim::uwb"]
        fn handle_uwb_request(facade_id: u32, packet: &[u8]);

        #[cxx_name = PatchCxx]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_patch(_facade_id: u32, _proto_bytes: &[u8]);

        #[cxx_name = GetCxx]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_get(_facade_id: u32) -> Vec<u8>;

        #[cxx_name = Reset]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_reset(_facade_id: u32);

        #[cxx_name = Remove]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_remove(_facade_id: u32);

        #[cxx_name = Add]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_add(_chip_id: u32) -> u32;

        #[cxx_name = Start]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_start();

        #[cxx_name = Stop]
        #[namespace = "netsim::uwb::facade"]
        pub fn uwb_stop();

    }

    unsafe extern "C++" {
        include!("controller/controller.h");

        #[namespace = "netsim::scene_controller"]
        type AddChipResult;
        fn get_chip_id(self: &AddChipResult) -> u32;
        fn get_device_id(self: &AddChipResult) -> u32;
        fn get_facade_id(self: &AddChipResult) -> u32;

        #[rust_name = "add_chip_cxx"]
        #[namespace = "netsim::scene_controller"]
        fn AddChipCxx(
            guid: &CxxString,
            device_name: &CxxString,
            chip_kind: u32,
            chip_name: &CxxString,
            manufacturer: &CxxString,
            product_name: &CxxString,
        ) -> UniquePtr<AddChipResult>;

        #[rust_name = "remove_chip"]
        #[namespace = "netsim::scene_controller"]
        fn RemoveChip(device_id: u32, chip_id: u32);

        #[rust_name = "get_devices"]
        #[namespace = "netsim::scene_controller"]
        fn GetDevices(
            request: &CxxString,
            response: Pin<&mut CxxString>,
            error_message: Pin<&mut CxxString>,
        ) -> u32;

        #[rust_name = "get_devices_bytes"]
        #[namespace = "netsim::scene_controller"]
        fn GetDevicesBytes(vec: &mut Vec<u8>) -> bool;

        #[rust_name = "get_facade_id"]
        #[namespace = "netsim::scene_controller"]
        fn GetFacadeId(chip_id: i32) -> i32;

        #[rust_name = "patch_device"]
        #[namespace = "netsim::scene_controller"]
        fn PatchDevice(
            request: &CxxString,
            response: Pin<&mut CxxString>,
            error_message: Pin<&mut CxxString>,
        ) -> u32;

        /// A C++ class which can be used to respond to a request.
        include!("frontend/server_response_writable.h");

        #[namespace = "netsim::frontend"]
        type CxxServerResponseWriter;

        #[namespace = "netsim::frontend"]
        fn put_ok_with_length(self: &CxxServerResponseWriter, mime_type: &CxxString, length: usize);

        #[namespace = "netsim::frontend"]
        fn put_chunk(self: &CxxServerResponseWriter, chunk: &[u8]);

        #[namespace = "netsim::frontend"]
        fn put_ok(self: &CxxServerResponseWriter, mime_type: &CxxString, body: &CxxString);

        #[namespace = "netsim::frontend"]
        fn put_error(self: &CxxServerResponseWriter, error_code: u32, error_message: &CxxString);

        include!("packet_hub/packet_hub.h");

        #[rust_name = "handle_request_cxx"]
        #[namespace = "netsim::packet_hub"]
        fn HandleRequestCxx(kind: u32, facade_id: u32, packet: &Vec<u8>, packet_type: u8);

    }
}

/// CxxServerResponseWriter is defined in server_response_writable.h
/// Wrapper struct allows the impl to discover the respective C++ methods
struct CxxServerResponseWriterWrapper<'a> {
    writer: Pin<&'a mut CxxServerResponseWriter>,
}

impl ServerResponseWritable for CxxServerResponseWriterWrapper<'_> {
    fn put_ok_with_length(&mut self, mime_type: &str, length: usize, _headers: StrHeaders) {
        let_cxx_string!(mime_type = mime_type);
        self.writer.put_ok_with_length(&mime_type, length);
    }
    fn put_chunk(&mut self, chunk: &[u8]) {
        self.writer.put_chunk(chunk);
    }
    fn put_ok(&mut self, mime_type: &str, body: &str, _headers: StrHeaders) {
        let_cxx_string!(mime_type = mime_type);
        let_cxx_string!(body = body);
        self.writer.put_ok(&mime_type, &body);
    }
    fn put_error(&mut self, error_code: u16, error_message: &str) {
        let_cxx_string!(error_message = error_message);
        self.writer.put_error(error_code.into(), &error_message);
    }

    fn put_ok_with_vec(&mut self, _mime_type: &str, _body: Vec<u8>, _headers: StrHeaders) {
        todo!()
    }
}
