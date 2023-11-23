use derive_getters::Getters;

#[derive(Getters)]
struct LifetimeAnnotated<'a> {
    val1: u64,
    val2: String,
    buffer: &'a [u8],
}

impl<'a> LifetimeAnnotated<'a> {
    pub fn new<T: Into<String>>(v1: u64, v2: T, buf: &'a [u8]) -> Self {
        LifetimeAnnotated {
            val1: v1,
            val2: v2.into(),
            buffer: buf,
        }
    }
}

#[derive(Getters)]
struct MultiAnnotated<'a, 'b, 'c, T> {
    v1: &'a str,
    v2: &'b [u8],
    v3: &'c T,
}

impl<'a, 'b, 'c, T> MultiAnnotated<'a, 'b, 'c, T> {
    pub fn new(v1: &'a str, v2: &'b [u8], v3: &'c T) -> Self {
        MultiAnnotated { v1, v2, v3 }
    }
}

#[derive(PartialEq, Eq)]
struct GenericType;

fn main() {
    let buffer: [u8; 12] = [88; 12];
    let la = LifetimeAnnotated::new(44, "Annot", &buffer);

    assert!(*la.val1() == 44);
    assert!(la.val2() == "Annot");
    assert!(la.buffer() == &buffer);

    let gt = GenericType;
    let ma = MultiAnnotated::new("Hello", &buffer, &gt);
    assert!(ma.v1() == "Hello");
    assert!(ma.v2() == &buffer);
    assert!(ma.v3() == &gt);
}
