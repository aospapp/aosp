#[test]
fn tests() {
    let t = trybuild::TestCases::new();
    t.pass("tests/01-legacy.rs");
    t.pass("tests/02-simple-single-generic.rs");
    t.pass("tests/03-simple-multi-generic.rs");
    t.pass("tests/04-simple-lifetime-annot.rs");
    t.pass("tests/05-skip-rename-attributes.rs");
    t.pass("tests/06-plays-with-others.rs");
    t.pass("tests/07-dissolve-basic.rs");
    t.pass("tests/08-dissolve-generic-and-ref.rs");    
}

#[test]
fn test_readme_deps() {
    version_sync::assert_markdown_deps_updated!("README.md");
}

#[test]
fn test_html_root_url() {
    version_sync::assert_html_root_url_updated!("src/lib.rs");
}
