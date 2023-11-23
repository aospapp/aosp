#![forbid(unsafe_code)]
#![rustfmt::skip]
#![allow(non_upper_case_globals)]
use binder::declare_binder_enum;
declare_binder_enum! {
  r#ByteEnum : [i8; 3] {
    r#FOO = 1,
    r#BAR = 2,
    r#BAZ = 3,
  }
}
pub(crate) mod mangled {
 pub use super::r#ByteEnum as _7_android_4_aidl_5_tests_8_ByteEnum;
}
