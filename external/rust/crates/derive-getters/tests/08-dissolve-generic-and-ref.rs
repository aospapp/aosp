//! Try with generics and references.

use derive_getters::{Getters, Dissolve};

#[derive(Copy, Clone, PartialEq, Eq)]
struct ConcreteType {
    a: u64,
    b: i64,
}

#[derive(Getters, Dissolve)]
struct MultiAnnotated<'a, 'b, 'c, T> {
    v1: &'a str,
    v2: &'b [u8],
    v3: &'c T,
    owned: String,
}

impl<'a, 'b, 'c, T> MultiAnnotated<'a, 'b, 'c, T> {
    pub fn new(v1: &'a str, v2: &'b [u8], v3: &'c T, owned: String) -> Self {
        MultiAnnotated { v1, v2, v3, owned }
    }
}

#[derive(Getters, Dissolve)]
#[dissolve(rename = "unmake")]
struct PolyAnnotated<'a, 'b, 'c, T> {
    v1: &'a str,
    v2: &'b [u8],
    v3: &'c T,
    owned: String,
}

impl<'a, 'b, 'c, T> PolyAnnotated<'a, 'b, 'c, T> {
    pub fn new(v1: &'a str, v2: &'b [u8], v3: &'c T, owned: String) -> Self {
        PolyAnnotated { v1, v2, v3, owned }
    }

    pub fn dissolve(self) -> String {
        self.owned
    }
}

fn main() {
    let buffer: [u8; 12] = [88; 12];
    let gt = ConcreteType { a: 44, b: -100 };
    let ma = MultiAnnotated::new("Hi", &buffer, &gt, "Another".to_owned());

    let (v1, v2, v3, owned) = ma.dissolve();
    assert!(v1 == "Hi");
    assert!(v2 == &buffer);
    assert!(*v3 == gt);
    assert!(owned == "Another");

    let pa = PolyAnnotated::new("Hi", &buffer, &gt, "Another".to_owned());
    let (v1, v2, v3, owned) = pa.unmake();
    assert!(v1 == "Hi");
    assert!(v2 == &buffer);
    assert!(*v3 == gt);
    assert!(owned == "Another");
}
    
