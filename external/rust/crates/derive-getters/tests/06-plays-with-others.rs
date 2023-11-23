use serde::{Serialize, Deserialize};
use derive_getters::Getters;

#[derive(Debug, Copy, Clone, PartialEq, Eq, Getters, Serialize, Deserialize)]
struct Plays {
    #[getter(rename = "skip_me")]
    #[getter(skip)]
    v1: u64,

    #[serde(skip)]
    #[getter(rename = "buffer")]
    v2: [u8; 12],

    #[getter(skip)]
    #[getter(rename = "keep_me")]
    #[serde(rename = "value3")]
    v3: u64,
}

impl Plays {
    pub fn new(v1: u64, v2: [u8; 12], v3: u64) -> Self {
        Plays { v1, v2, v3 }
    }
}

fn main() {
    let buffer: [u8; 12] = [88; 12];
    let c = Plays::new(46, buffer, 64);
    //assert!(c.skip_me() == "Hello");
    assert!(c.buffer() == &buffer);
    //assert!(c.v3() == &gt);
    assert!(*c.keep_me() == 64);
}
