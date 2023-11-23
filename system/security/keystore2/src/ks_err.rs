// Copyright 2020, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! A ks_err macro that expands error messages to include the file and line number

///
/// # Examples
///
/// ```
/// use crate::ks_err;
///
/// ks_err!("Key is expired.");
/// Result:
/// "src/lib.rs:7 Key is expired."
/// ```
#[macro_export]
macro_rules! ks_err {
    { $($arg:tt)+ } => {
        format!("{}:{}: {}", file!(), line!(), format_args!($($arg)+))
    };
    {} => {
        format!("{}:{}", file!(), line!())
    };
}
