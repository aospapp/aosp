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

//! Response module for micro HTTP server.
//!
//! This library implements the basic parts of Response Message from
//! (RFC 5322)[ https://www.rfc-editor.org/rfc/rfc5322.html] "HTTP
//! Message Format."
//!
//! This library is only used for serving the netsim client and is not
//! meant to implement all aspects of RFC 5322.

use super::http_request::{HttpHeaders, StrHeaders};

pub struct HttpResponse {
    pub status_code: u16,
    pub headers: HttpHeaders,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn new_ok_with_length(content_type: &str, length: usize) -> HttpResponse {
        let body = Vec::new();
        HttpResponse {
            status_code: 200,
            headers: HttpHeaders::new_with_headers(&[
                ("Content-Type", content_type),
                ("Content-Length", length.to_string().as_str()),
            ]),
            body,
        }
    }

    pub fn new_ok(content_type: &str, body: Vec<u8>) -> HttpResponse {
        HttpResponse {
            status_code: 200,
            headers: HttpHeaders::new_with_headers(&[
                ("Content-Type", content_type),
                ("Content-Length", &body.len().to_string()),
            ]),
            body,
        }
    }

    pub fn new_error(status_code: u16, body: Vec<u8>) -> HttpResponse {
        HttpResponse {
            status_code,
            headers: HttpHeaders::new_with_headers(&[
                ("Content-Type", "text/plain"),
                ("Content-Length", &body.len().to_string()),
            ]),
            body,
        }
    }

    pub fn add_headers(&mut self, headers: StrHeaders) {
        for (header_key, header_value) in headers {
            self.headers.add_header(header_key, header_value)
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::http_server::server_response::{ServerResponseWritable, ServerResponseWriter};
    use std::io::Cursor;

    #[test]
    fn test_write_to() {
        let mut stream = Cursor::new(Vec::new());
        let mut writer = ServerResponseWriter::new(&mut stream);
        writer.put_ok_with_vec("text/plain", b"Hello World".to_vec(), &[]);
        let written_bytes = stream.get_ref();
        let expected_bytes =
            b"HTTP/1.1 200\r\nContent-Type: text/plain\r\nContent-Length: 11\r\n\r\nHello World";
        assert_eq!(written_bytes, expected_bytes);
    }
}
