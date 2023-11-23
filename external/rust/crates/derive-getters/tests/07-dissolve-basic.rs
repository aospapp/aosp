//! First tests for new `dissolve` functionality
use serde::{Serialize, Deserialize};
use derive_getters::Dissolve;

#[derive(Dissolve, Serialize, Deserialize)]
struct Number {
    num: u64,
}

#[derive(Copy, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct Inner {
    a: u64,
    b: i64,
}

#[derive(Dissolve, Serialize, Deserialize)]
struct ManyStuff {
    name: String,
    price: f64,
    count: usize,
    inner: Inner,
}

#[derive(Dissolve, Serialize, Deserialize)]
#[dissolve(rename = "shatter")]
struct LotsOfStuff {
    name: String,
    price: f64,
    count: usize,
    inner: Inner,
}

impl LotsOfStuff {
    fn dissolve(&self) -> f64 {
        self.inner.b as f64 * self.price
    }
}

fn main() {
    let n = Number { num: 64 };
    let number = n.dissolve();
    assert!(number == 64);

    let inner = Inner { a: 22, b: -33 };
    let stuff = ManyStuff {
        name: "Hogie".to_owned(),
        price: 123.4f64,
        count: 100,
        inner,
    };
    let (n, p, c, i) = stuff.dissolve();
    assert!(n == "Hogie");
    assert!(p == 123.4f64);
    assert!(c == 100);
    assert!(i == inner);
    
    //let _ = stuff.dissolve();

    let stuff = LotsOfStuff {
        name: "Hogie".to_owned(),
        price: 123.4f64,
        count: 100,
        inner,
    };
    let (n, p, c, i) = stuff.shatter();
    assert!(n == "Hogie");
    assert!(p == 123.4f64);
    assert!(c == 100);
    assert!(i == inner);

    //let _ = stuff.shatter();
}
