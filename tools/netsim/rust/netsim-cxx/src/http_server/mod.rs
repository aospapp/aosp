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

pub(crate) mod http_request;
mod http_response;
mod http_router;
pub(crate) mod server_response;
mod thread_pool;

use crate::captures::handlers::*;
use crate::http_server::http_request::HttpRequest;
use crate::http_server::http_router::Router;
use crate::http_server::server_response::{
    ResponseWritable, ServerResponseWritable, ServerResponseWriter,
};
use crate::version::VERSION;

use crate::http_server::thread_pool::ThreadPool;

use crate::ffi::get_devices;
use crate::ffi::patch_device;
use cxx::let_cxx_string;
use std::collections::HashSet;
use std::ffi::OsStr;
use std::fs;
use std::io::BufReader;
use std::net::TcpListener;
use std::net::TcpStream;
use std::path::Path;
use std::path::PathBuf;
use std::sync::Arc;

const PATH_PREFIXES: [&str; 3] = ["js", "assets", "node_modules/tslib"];

pub fn run_http_server() {
    let listener = match TcpListener::bind("127.0.0.1:7681") {
        Ok(listener) => listener,
        Err(e) => {
            eprintln!("netsimd: bind error in netsimd frontend http server. {}", e);
            return;
        }
    };
    let pool = ThreadPool::new(4);
    println!("netsimd: Frontend http server is listening on http://localhost:7681");
    let valid_files = Arc::new(create_filename_hash_set());
    for stream in listener.incoming() {
        let stream = stream.unwrap();
        let valid_files = valid_files.clone();
        pool.execute(move || {
            handle_connection(stream, valid_files);
        });
    }

    println!("netsimd: Shutting down frontend http server.");
}

fn ui_path(suffix: &str) -> PathBuf {
    let mut path = std::env::current_exe().unwrap();
    path.pop();
    path.push("netsim-ui");
    for subpath in suffix.split('/') {
        path.push(subpath);
    }
    path
}

fn create_filename_hash_set() -> HashSet<String> {
    let mut valid_files: HashSet<String> = HashSet::new();
    for path_prefix in PATH_PREFIXES {
        let dir_path = ui_path(path_prefix);
        if let Ok(mut file) = fs::read_dir(dir_path) {
            while let Some(Ok(entry)) = file.next() {
                valid_files.insert(entry.path().to_str().unwrap().to_string());
            }
        } else {
            println!("netsim-ui doesn't exist");
        }
    }
    valid_files
}

fn check_valid_file_path(path: &str, valid_files: &HashSet<String>) -> bool {
    let filepath = match path.strip_prefix('/') {
        Some(stripped_path) => ui_path(stripped_path),
        None => ui_path(path),
    };
    valid_files.contains(filepath.as_path().to_str().unwrap())
}

fn to_content_type(file_path: &Path) -> &str {
    match file_path.extension().and_then(OsStr::to_str) {
        Some("html") => "text/html",
        Some("txt") => "text/plain",
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("png") => "image/png",
        Some("js") => "application/javascript",
        Some("svg") => "image/svg+xml",
        _ => "application/octet-stream",
    }
}

fn handle_file(method: &str, path: &str, writer: ResponseWritable) {
    if method == "GET" {
        let filepath = match path.strip_prefix('/') {
            Some(stripped_path) => ui_path(stripped_path),
            None => ui_path(path),
        };
        if let Ok(body) = fs::read(&filepath) {
            writer.put_ok_with_vec(to_content_type(&filepath), body, &[]);
            return;
        }
    }
    let body = format!("404 not found (netsim): handle_file with unknown path {path}");
    writer.put_error(404, body.as_str());
}

fn handle_pcap_file(request: &HttpRequest, id: &str, writer: ResponseWritable) {
    if &request.method == "GET" {
        let mut filepath = std::env::current_exe().unwrap();
        filepath.pop();
        filepath.push("/tmp");
        filepath.push(format!("{}-hci.pcap", id.replace("%20", " ")));
        if let Ok(body) = fs::read(&filepath) {
            writer.put_ok_with_vec(to_content_type(&filepath), body, &[]);
            return;
        }
    }
    let body = "404 not found (netsim): pcap file not exists for the device".to_string();
    writer.put_error(404, body.as_str());
}

// TODO handlers accept additional "context" including filepath
fn handle_index(request: &HttpRequest, _param: &str, writer: ResponseWritable) {
    handle_file(&request.method, "index.html", writer)
}

fn handle_static(request: &HttpRequest, path: &str, writer: ResponseWritable) {
    // The path verification happens in the closure wrapper around handle_static.
    handle_file(&request.method, path, writer)
}

fn handle_version(_request: &HttpRequest, _param: &str, writer: ResponseWritable) {
    let body = format!("{{\"version\": \"{}\"}}", VERSION);
    writer.put_ok("text/plain", body.as_str(), &[]);
}

fn handle_devices(request: &HttpRequest, _param: &str, writer: ResponseWritable) {
    if &request.method == "GET" {
        let_cxx_string!(request = "");
        let_cxx_string!(response = "");
        let_cxx_string!(error_message = "");
        let status = get_devices(&request, response.as_mut(), error_message.as_mut());
        if status == 200 {
            writer.put_ok("text/plain", response.to_string().as_str(), &[]);
        } else {
            let body = format!("404 Not found (netsim): {:?}", error_message.to_string());
            writer.put_error(404, body.as_str());
        }
    } else if &request.method == "PATCH" {
        let_cxx_string!(new_request = &request.body);
        let_cxx_string!(response = "");
        let_cxx_string!(error_message = "");
        let status = patch_device(&new_request, response.as_mut(), error_message.as_mut());
        if status == 200 {
            writer.put_ok("text/plain", response.to_string().as_str(), &[]);
        } else {
            let body = format!("404 Not found (netsim): {:?}", error_message.to_string());
            writer.put_error(404, body.as_str());
        }
    } else {
        let body = format!(
            "404 Not found (netsim): {:?} is not a valid method for this route",
            request.method.to_string()
        );
        writer.put_error(404, body.as_str());
    }
}

fn handle_connection(mut stream: TcpStream, valid_files: Arc<HashSet<String>>) {
    let mut router = Router::new();
    router.add_route("/", Box::new(handle_index));
    router.add_route("/version", Box::new(handle_version));
    router.add_route("/v1/devices", Box::new(handle_devices));
    router.add_route(r"/pcap/{id}", Box::new(handle_pcap_file));
    router.add_route(r"/v1/captures", Box::new(handle_capture));
    router.add_route(r"/v1/captures/{id}", Box::new(handle_capture));

    // A closure for checking if path is a static file we wish to serve, and call handle_static
    let handle_static_wrapper =
        move |request: &HttpRequest, path: &str, writer: ResponseWritable| {
            for prefix in PATH_PREFIXES {
                let new_path = format!("{prefix}/{path}");
                if check_valid_file_path(new_path.as_str(), &valid_files) {
                    handle_static(request, new_path.as_str(), writer);
                    return;
                }
            }
            let body = format!("404 not found (netsim): Invalid path {path}");
            writer.put_error(404, body.as_str());
        };

    // Connecting all path prefixes to handle_static_wrapper
    for prefix in PATH_PREFIXES {
        router.add_route(
            format!(r"/{prefix}/{{path}}").as_str(),
            Box::new(handle_static_wrapper.clone()),
        )
    }

    if let Ok(request) = HttpRequest::parse::<&TcpStream>(&mut BufReader::new(&stream)) {
        let mut response_writer = ServerResponseWriter::new(&mut stream);
        router.handle_request(&request, &mut response_writer);
    } else {
        let mut response_writer = ServerResponseWriter::new(&mut stream);
        let body = "404 not found (netsim): parse header failed";
        response_writer.put_error(404, body);
    };
}
