use derive_getters::Getters;

#[derive(Getters)]
struct SimpleSingleGeneric<T> {
    concrete: u16,
    generic: T,
}

impl<T> SimpleSingleGeneric<T> {
    pub fn new(concrete: u16, generic: T) -> Self {
        SimpleSingleGeneric { concrete, generic }
    }
}

#[derive(PartialEq, Eq, PartialOrd, Ord)]
pub enum Enumeration {
    One,
    Two,
    Three,
}

#[derive(Getters)]
struct InvolvedSingleGeneric<T: Eq + Ord> {
    concrete: u16,
    generic: T,
}

impl <T: Eq + Ord> InvolvedSingleGeneric<T> {
    pub fn new(concrete: u16, generic: T) -> Self {
        InvolvedSingleGeneric { concrete, generic }
    }
}

#[derive(Getters)]
struct WhereClauseSingleGeneric<T>
where T: Eq + Ord
{
    concrete: u16,
    generic: T,
}

impl<T> WhereClauseSingleGeneric<T>
    where T: Eq + Ord
{
    pub fn new(concrete: u16, generic: T) -> Self {
        WhereClauseSingleGeneric { concrete, generic }
    }
}   

fn main() {
    let ssg = SimpleSingleGeneric::new(23, "Hello".to_string());
    assert!(*ssg.concrete() == 23);
    assert!(ssg.generic() == "Hello");

    let isg = InvolvedSingleGeneric::new(44, Enumeration::Two);
    assert!(*isg.concrete() == 44);
    assert!(*isg.generic() == Enumeration::Two);

    let wcsg = WhereClauseSingleGeneric::new(99, Enumeration::Three);
    assert!(*wcsg.concrete() == 99);
    assert!(*wcsg.generic() == Enumeration::Three);
}
