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

// Opening Browser on Linux and MacOS

use std::ffi::OsStr;
#[cfg(any(target_os = "linux", target_os = "macos", target_os = "windows"))]
use std::process::Command;

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
pub fn open<T: AsRef<OsStr>>(path: T) {
    let path = path.as_ref();
    println!("Unsupported OS. Open this url:{:?}", path)
}

#[cfg(target_os = "linux")]
pub fn open<T: AsRef<OsStr>>(path: T) {
    let path = path.as_ref();
    let open_handlers = ["xdg-open", "gnome-open", "kde-open"];

    for command in &open_handlers {
        if let Ok(_output) = Command::new(command).arg(path).output() {
            return;
        }
    }
    println!("xdg-open, gnome-open, kde-open not working (linux). Open this url:{:?}", path);
}

#[cfg(target_os = "macos")]
pub fn open<T: AsRef<OsStr>>(path: T) {
    let path = path.as_ref();
    if let Ok(_output) = Command::new("/usr/bin/open").arg(path).output() {
        return;
    }
    println!("/usr/bin/open not working (macos). Open this url:{:?}", path);
}

#[cfg(target_os = "windows")]
pub fn open<T: AsRef<OsStr>>(path: T) {
    let path = path.as_ref();
    if let Ok(_output) = Command::new("cmd.exe").args(["/C", "start"]).arg(path).output() {
        return;
    } else if let Ok(_output) = Command::new("explorer").arg(path).output() {
        return;
    }
    println!("'start' and 'explorer' command not supported (windows). Open this url:{:?}", path);
}
