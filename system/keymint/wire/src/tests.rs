use super::*;
use crate::cbor::value::Value;
use alloc::vec;

#[test]
fn test_read_to_value_ok() {
    let tests = vec![
        ("01", Value::Integer(1.into())),
        ("40", Value::Bytes(vec![])),
        ("60", Value::Text(String::new())),
    ];
    for (hexdata, want) in tests {
        let data = hex::decode(hexdata).unwrap();
        let got = read_to_value(&data).unwrap();
        assert_eq!(got, want, "failed for {}", hexdata);
    }
}

#[test]
fn test_read_to_value_fail() {
    let tests = vec![
        ("0101", CborError::ExtraneousData),
        ("43", CborError::DecodeFailed(cbor::de::Error::Io(EndOfFile))),
        ("8001", CborError::ExtraneousData),
    ];
    for (hexdata, want_err) in tests {
        let data = hex::decode(hexdata).unwrap();
        let got_err = read_to_value(&data).expect_err("decoding expected to fail");
        assert_eq!(format!("{:?}", got_err), format!("{:?}", want_err), "failed for {}", hexdata);
    }
}
