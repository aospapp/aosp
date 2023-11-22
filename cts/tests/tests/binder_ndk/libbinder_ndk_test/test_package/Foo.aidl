package test_package;

import test_package.Bar;

parcelable Foo {
    String a="FOO";
    int b=42;
    float c=3.14f;
    Bar d;
    Bar e;
    int f=3;
    // This field doesn't exist in version 1.
    @nullable String[] g;
}
