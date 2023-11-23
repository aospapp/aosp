//! This library provides two derive macros. One, `Getters` for autogenerating getters and
//! `Dissolve` for consuming a struct returning a tuple of all fields. They can only be
//! used on named structs.
//!
//! # Derives
//!
//! Only named structs can derive `Getters` or `Dissolve`.
//!
//! # `Getter` methods generated
//!
//! The getter methods generated shall bear the same name as the struct fields and be
//! publicly visible. The methods return an immutable reference to the struct field of the
//! same name. If there is already a method defined with that name there'll be a collision.
//! In these cases one of two attributes can be set to either `skip` or `rename` the getter.
//! 
//!
//! # `Getters` Usage
//!
//! In lib.rs or main.rs;
//!
//! ```edition2018
//! use derive_getters::Getters;
//!
//! #[derive(Getters)]
//! struct Number {
//!     num: u64,    
//! }
//! 
//! fn main() {
//!     let number = Number { num: 655 };
//!     assert!(number.num() == &655);
//! }
//! ```
//!
//! Here, a method called `num()` has been created for the `Number` struct which gives a
//! reference to the `num` field.
//!
//! This macro can also derive on structs that have simple generic types. For example;
//!
//! ```edition2018
//! # use derive_getters::Getters;
//! #[derive(Getters)]
//! struct Generic<T, U> {
//!     gen_t: T,
//!     gen_u: U,
//! }
//! #
//! # fn main() { }
//! ```
//!
//! The macro can also handle generic types with trait bounds. For example;
//! ```edition2018
//! # use derive_getters::Getters;
//! #[derive(Getters)]
//! struct Generic<T: Clone, U: Copy> {
//!     gen_t: T,
//!     gen_u: U,
//! }
//! #
//! # fn main() { }
//! ```
//! The trait bounds can also be declared in a `where` clause.
//!
//! Additionaly, simple lifetimes are OK too;
//! ```edition2018
//! # use derive_getters::Getters;
//! #[derive(Getters)]
//! struct Annotated<'a, 'b, T> {
//!     stuff: &'a T,
//!     comp: &'b str,
//!     num: u64,
//! }
//! #
//! # fn main() { }
//! ```
//!
//! # `Getter` Attributes
//! Getters can be further configured to either skip or rename a getter.
//!
//! * #[getter(skip)]
//! Will skip generating a getter for the field being decorated.
//!
//! * #[getter(rename = "name")]
//! Changes the name of the getter (default is the field name) to "name".
//!
//!```edition2018
//! # use derive_getters::Getters;
//! #[derive(Getters)]
//! struct Attributed {
//!     keep_me: u64,
//!
//!     #[getter(skip)]
//!     skip_me: u64,
//!
//!     #[getter(rename = "number")]
//!     rename_me: u64,
//! }
//! #
//! # fn main() { }
//! ```
//!
//! # `Dissolve` method generated
//!
//! Deriving `Dissolve` on a named struct will generate a method `dissolve(self)` which
//! shall return a tuple of all struct fields in the order they were defined. Calling this
//! method consumes the struct. The name of this method can be changed with an attribute.
//!
//! # `Dissolve` usage
//!
//! ```edition2018
//! # use derive_getters::Dissolve;
//! #[derive(Dissolve)]
//! struct Stuff {
//!     name: String,
//!     price: f64,
//!     count: usize,
//! }
//! 
//! fn main() {
//!     let stuff = Stuff {
//!         name: "Hogie".to_owned(),
//!         price: 123.4f64,
//!         count: 100,
//!     };
//!
//!     let (n, p, c) = stuff.dissolve();
//!     assert!(n == "Hogie");
//!     assert!(p == 123.4f64);
//!     assert!(c == 100);
//! }
//! ```
//!
//! # `Dissolve` Attributes
//! You can rename the `dissolve` function by using a struct attribute.
//!
//! * #[dissolve(rename = "name")]
//!
//! ```edition2018
//! # use derive_getters::Dissolve;
//! #[derive(Dissolve)]
//! #[dissolve(rename = "shatter")]
//! struct Numbers {
//!     a: u64,
//!     b: i64,
//!     c: f64,
//! }
//! #
//! # fn main() { }
//! ```
//!
//! # Panics
//!
//! If `Getters` or `Dissolve` are derived on unit or unnamed structs, enums or unions.
//!
//! # Cannot Do
//! Const generics aren't handled by this macro nor are they tested.
use std::convert::TryFrom;

extern crate proc_macro;
use syn::{DeriveInput, parse_macro_input};

mod faultmsg;
mod dissolve;
mod getters;
mod extract;

/// Generate getter methods for all named struct fields in a seperate struct `impl` block.
/// Getter methods share the name of the field they're 'getting'. Methods return an
/// immutable reference to the field.
#[proc_macro_derive(Getters, attributes(getter))]
pub fn getters(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let ast = parse_macro_input!(input as DeriveInput);
    
    getters::NamedStruct::try_from(&ast)
        .map(|ns| ns.emit())
        .unwrap_or_else(|err| err.to_compile_error())
        .into()
}

/// Produce a `dissolve` method that consumes the named struct returning a tuple of all the
/// the struct fields.
#[proc_macro_derive(Dissolve, attributes(dissolve))]
pub fn dissolve(input: proc_macro::TokenStream) -> proc_macro::TokenStream {
    let ast = parse_macro_input!(input as DeriveInput);

    dissolve::NamedStruct::try_from(&ast)
        .map(|ns| ns.emit())
        .unwrap_or_else(|err| err.to_compile_error())
        .into()
}
