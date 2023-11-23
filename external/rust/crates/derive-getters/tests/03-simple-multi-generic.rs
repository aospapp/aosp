use std::ops::Div;

use derive_getters::Getters;

#[derive(Getters)]
struct SimpleMultiGeneric<T, U, V> {
    concrete: u16,
    generic_t: T,
    generic_u: U,
    generic_v: V,
}

impl<T, U, V> SimpleMultiGeneric<T, U, V> {
    pub fn new(concrete: u16, generic_t: T, generic_u: U, generic_v: V) -> Self {
        SimpleMultiGeneric { concrete, generic_t, generic_u, generic_v }
    }
}

#[derive(PartialEq, Eq, PartialOrd, Ord)]
pub enum Enumeration {
    One,
    Two,
    Three,
}

#[derive(Getters)]
struct InvolvedMultiGeneric<T: Eq + Ord, U: Div, V: Clone> {
    concrete: u16,
    generic_t: T,
    generic_u: U,
    generic_v: V,
}

impl <T: Eq + Ord, U: Div, V: Clone> InvolvedMultiGeneric<T, U, V> {
    pub fn new(concrete: u16, generic_t: T, generic_u: U, generic_v: V) -> Self {
        InvolvedMultiGeneric { concrete, generic_t, generic_u, generic_v }
    }
}


#[derive(Getters)]
struct WhereClauseMultiGeneric<T, U, V>
where T: Eq + Ord,
      U: Div,
      V: Clone,
{
    concrete: u16,
    generic_t: T,
    generic_u: U,
    generic_v: V,
}

impl<T, U, V> WhereClauseMultiGeneric<T, U, V>
where T: Eq + Ord,
      U: Div,
      V: Clone,
{
    pub fn new(concrete: u16, generic_t: T, generic_u: U, generic_v: V) -> Self {
        WhereClauseMultiGeneric { concrete, generic_t, generic_u, generic_v }
    }
} 

fn main() {
    let smg = SimpleMultiGeneric::new(
        23, "Hello".to_string(), 55f64, [1, 2, 3],
    );
    assert!(*smg.concrete() == 23);
    assert!(smg.generic_t() == "Hello");
    assert!(*smg.generic_u() == 55f64);
    assert!(*smg.generic_v() == [1, 2, 3]);

    let img = InvolvedMultiGeneric::new(
        44, Enumeration::Two, 1024f32, "String".to_string(),
    );
    assert!(*img.concrete() == 44);
    assert!(*img.generic_t() == Enumeration::Two);
    assert!(*img.generic_u() == 1024f32);
    assert!(img.generic_v() == "String");

    let wcmg = WhereClauseMultiGeneric::new(
        99, Enumeration::Three, 1222f64, "Might".to_string()
    );
    assert!(*wcmg.concrete() == 99);
    assert!(*wcmg.generic_t() == Enumeration::Three);
    assert!(*wcmg.generic_u() == 1222f64);
    assert!(wcmg.generic_v() == "Might");
}
