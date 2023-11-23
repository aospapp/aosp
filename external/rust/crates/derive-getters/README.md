# Derive Getters

Simple `Getters` derive macro for generating field getter methods on a named struct. Included is an additional derive, `Dissolve`, that consumes the named struct returning a tuple of all fields in the order they were declared.

The need for the `Getters` macro came about when I was making various data structures for JSON to deserialize into. These data structures had many fields in them to access and they weren't going to change once created. One could use `pub` everywhere but that would enable mutating the fields which is what this derive aims to avoid.

Getters will be generated according to [convention](https://github.com/rust-lang/rfcs/blob/master/text/0344-conventions-galore.md#gettersetter-apis). This means that the generated methods will reside within the struct namespace.

With regards to `Dissolve`, sometimes during conversion a structure must be consumed. One easy way to do this is to return a tuple of all the structs fields. Thus `Dissolve` can be considered a 'get (move) everything' method call.

## What this crate won't do
There are no mutable getters and it's not planned. There are no setters either nor will there ever be.

## Rust Docs
[Documentation is here.](https://docs.rs/derive-getters/0.2.0)

## Installation

Add to your `Cargo.toml`:
```toml
[dependencies]
derive-getters = "0.2.0"
```

Then import the `Getters` or `Dissolve` macro in whichever module it's needed (assuming 2018 edition).
```rust
use derive_getters::{Getters, Dissolve};

```
Otherwise just import at crate root.
```rust
#[macro_use]
extern crate derive_getters;
```

## Usage

When you have a struct you want to automatically derive getters for... Just add the derive at the top like so;
```rust
#[derive(Getters)]
pub struct MyCheesyStruct {
    x: i64,
    y: i64,
}
```

A new impl will be produced for `MyCheesyStruct`.
```rust
impl MyCheesyStruct {
    pub fn x(&self) -> &i64 {
        &self.x
    }

    pub fn y(&self) -> &i64 {
        &self.y
    }
}
```

This crate can also handle structs with simple generic parameters and lifetime annotations. Check [docs](https://docs.rs/derive-getters/0.2.0) for further details.
```rust
#[derive(Getters)]
pub struct StructWithGeneric<'a, T> {
    concrete: f64,
    generic: T,
    text: &'a str,
}
```

With `Dissolve`, use it like so;
```rust
#[derive(Dissolve)]
pub struct Solid {
    a: u64,
    b: f64,
    c: i64,
}
```

An impl will be produced for `Solid` like so;
```rust
impl Solid {
    pub fn dissolve(self) -> (u64, f64, i64) {
      (self.a, self.b, self.c)
    }
}
```

### Attributes
This macro comes with two optional field attributes for `Getters`.
* `#[getter(skip)]` to skip generating getters for a field.
* `#[getter(rename = "name")]` to change the getter name to "name".

And one optional struct attribute for `Dissolve`.
* `#[dissolve(rename = "name")]` to change the name of the dissolve function to "name".

## Caveats
1. Will not work on unit structs, tuples or enums. Derive `Getters` or `Dissolve` over them and the macro will chuck a wobbly.
2. All getter methods return an immutable reference, `&`, to their field. This means for some types it can get awkward.

## Alternatives
[getset](https://github.com/Hoverbear/getset).
