//! Legacy tests from 0.0.7 version.

use derive_getters::Getters;

#[derive(Getters)]
struct Number {
    num: u64,    
}

fn number_num() {
    let number = Number { num: 655 };
    assert!(number.num() == &655);
}

#[derive(Getters)]
struct ManyNumbers {
    integer: u64,
    floating: f64,
    byte: u8,
}

fn many_numbers() {
    let numbers = ManyNumbers {
        integer: 655,
        floating: 45.5,
        byte: 122,
    };

    assert!(numbers.integer() == &655);
    assert!(numbers.floating() == &45.5);
    assert!(numbers.byte() == &122);
}

#[derive(Getters)]
struct Textual {
    author: Option<String>,
    heading: String,    
    lines: Vec<String>,    
}

fn textual_struct() {
    let article = Textual {
        author: None,
        heading: "abcdefg".to_string(),
        lines: vec![
            "hijk".to_string(),
            "lmno".to_string(),
            "pqrs".to_string(),
            "tuvw".to_string(),
        ],
    };

    assert!(article.author() == &None);
    assert!(article.heading() == "abcdefg");
    assert!(article.lines().len() == 4);
    assert!(article.lines()[0] == "hijk");
    assert!(article.lines()[1] == "lmno");
    assert!(article.lines()[2] == "pqrs");
    assert!(article.lines()[3] == "tuvw");

    let book = Textual {
        author: Some("name".to_string()),
        heading: "1234".to_string(),
        lines: vec![
            "2345".to_string(),
            "3456".to_string(),
            "4567".to_string(),
            "5678".to_string(),
        ],       
    };

    assert!(book.author() == &Some("name".to_string()));
}

/// There shouldn't be any dead code warnings on unused methods, only on unused slots which
/// are not the libraries fault.
#[derive(Getters)]
struct DeadCode {
    x: u64,
    y: u64,
    z: u64,
}

#[test]
fn dead_code_struct() {
    let dc = DeadCode {
        x: 1,
        y: 2,
        z: 3,
    };

    assert!(*dc.x() == 1);
}

fn main() {
    number_num();
    many_numbers();
    textual_struct();
}

