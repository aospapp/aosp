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

//! Request library for micro HTTP server.
//!
//! This library implements the basic parts of Request Message from
//! (RFC 5322)[ https://www.rfc-editor.org/rfc/rfc5322.html] "HTTP
//! Message Format."
//!
//! This library is only used for serving the netsim client and is not
//! meant to implement all aspects of RFC 5322. In particular,
//! this library does not implement the following:
//! * header field body with multiple lines (section 3.2.2)
//! * limits on the lengths of the header section or header field
//!
//! The main function is `HttpRequest::parse` which can be called
//! repeatedly.

use std::io::BufRead;
use std::io::BufReader;
use std::io::Read;

pub type StrHeaders<'a> = &'a [(&'a str, &'a str)];

#[derive(Debug)]
pub struct HttpHeaders {
    pub headers: Vec<(String, String)>,
}

impl HttpHeaders {
    pub fn new() -> Self {
        Self { headers: Vec::new() }
    }

    #[allow(dead_code)]
    pub fn get(&self, key: &str) -> Option<String> {
        let key = key.to_ascii_lowercase();
        for (name, value) in self.headers.iter() {
            if name.to_ascii_lowercase() == key {
                return Some(value.to_string());
            }
        }
        None
    }

    #[allow(dead_code)]
    pub fn new_with_headers(str_headers: StrHeaders) -> HttpHeaders {
        HttpHeaders {
            headers: str_headers
                .iter()
                .map(|(key, value)| -> (String, String) { (key.to_string(), value.to_string()) })
                .collect(),
        }
    }

    pub fn iter(&self) -> impl Iterator<Item = &(String, String)> {
        self.headers.iter()
    }

    pub fn add_header(&mut self, header_key: &str, header_value: &str) {
        self.headers.push((header_key.to_owned(), header_value.to_owned()));
    }

    // Same in an impl PartialEq does not work for assert_eq!
    // so use a method for unit tests
    #[allow(dead_code)]
    pub fn eq(&self, other: &[(&str, &str)]) -> bool {
        self.headers.iter().zip(other.iter()).all(|(a, b)| a.0 == b.0 && a.1 == b.1)
    }
}

pub struct HttpRequest {
    pub method: String,
    pub uri: String,
    pub version: String,
    pub headers: HttpHeaders,
    pub body: Vec<u8>,
}

impl HttpRequest {
    // Parse an HTTP request from a BufReader

    // Clippy does not notice the call to resize, so disable the zero byte vec warning.
    // https://github.com/rust-lang/rust-clippy/issues/9274
    #[allow(clippy::read_zero_byte_vec)]
    pub fn parse<T>(reader: &mut BufReader<T>) -> Result<HttpRequest, String>
    where
        T: std::io::Read,
    {
        let (method, uri, version) = parse_request_line::<T>(reader)?;
        let headers = parse_header_section::<T>(reader)?;
        let mut body = Vec::new();
        if let Some(len) = get_content_length(&headers) {
            body.resize(len, 0);
            reader.read_exact(&mut body).map_err(|e| format!("Failed to read body: {e}"))?;
        }
        Ok(HttpRequest { method, uri, version, headers, body })
    }
}

// Parse the request line of an HTTP request, which contains the method, URI, and version
fn parse_request_line<T>(reader: &mut BufReader<T>) -> Result<(String, String, String), String>
where
    T: std::io::Read,
{
    let mut line = String::new();
    reader.read_line(&mut line).map_err(|e| format!("Failed to read request line: {e}"))?;
    let mut parts = line.split_whitespace();
    let method = parts.next().ok_or("Invalid request line, missing method")?;
    let uri = parts.next().ok_or("Invalid request line, missing uri")?;
    let version = parts.next().ok_or("Invalid request line, missing version")?;
    Ok((method.to_string(), uri.to_string(), version.to_string()))
}

// Parse the Headers Section from (RFC 5322)[https://www.rfc-editor.org/rfc/rfc5322.html]
// "HTTP Message Format."
fn parse_header_section<T>(reader: &mut BufReader<T>) -> Result<HttpHeaders, String>
where
    T: std::io::Read,
{
    let mut headers = HttpHeaders::new();
    for line in reader.lines() {
        let line = line.map_err(|e| format!("Failed to parse headers: {e}"))?;
        if let Some((name, value)) = line.split_once(':') {
            headers.add_header(name, value.trim());
        } else if line.len() > 1 {
            // no colon in a header line
            return Err(format!("Invalid header line: {line}"));
        } else {
            // empty line marks the end of headers
            break;
        }
    }
    Ok(headers)
}

fn get_content_length(headers: &HttpHeaders) -> Option<usize> {
    if let Some(value) = headers.get("Content-Length") {
        match value.parse::<usize>() {
            Ok(n) => return Some(n),
            Err(_) => return None,
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse() {
        let request = concat!(
            "GET /index.html HTTP/1.1\r\n",
            "Host: example.com\r\nContent-Length: 13\r\n\r\n",
            "Hello World\r\n"
        );
        let mut reader = BufReader::new(request.as_bytes());
        let http_request = HttpRequest::parse::<&[u8]>(&mut reader).unwrap();
        assert_eq!(http_request.method, "GET");
        assert_eq!(http_request.uri, "/index.html");
        assert_eq!(http_request.version, "HTTP/1.1");
        assert!(http_request.headers.eq(&[("Host", "example.com"), ("Content-Length", "13")]));
        assert_eq!(http_request.body, b"Hello World\r\n".to_vec());
    }

    #[test]
    fn test_parse_without_body() {
        let request = concat!("GET /index.html HTTP/1.1\r\n", "Host: example.com\r\n\r\n");
        let mut reader = BufReader::new(request.as_bytes());
        let http_request = HttpRequest::parse::<&[u8]>(&mut reader).unwrap();
        assert_eq!(http_request.method, "GET");
        assert_eq!(http_request.uri, "/index.html");
        assert_eq!(http_request.version, "HTTP/1.1");
        assert!(http_request.headers.eq(&[("Host", "example.com")]));
        assert_eq!(http_request.body, Vec::<u8>::new());
    }

    #[test]
    fn test_parse_without_content_length() {
        let request =
            concat!("GET /index.html HTTP/1.1\r\n", "Host: example.com\r\n\r\n", "Hello World\r\n");
        let mut reader = BufReader::new(request.as_bytes());
        let http_request = HttpRequest::parse::<&[u8]>(&mut reader).unwrap();
        assert_eq!(http_request.method, "GET");
        assert_eq!(http_request.uri, "/index.html");
        assert_eq!(http_request.version, "HTTP/1.1");
        assert!(http_request.headers.eq(&[("Host", "example.com")]));
        assert_eq!(http_request.body, b"");
    }
}
