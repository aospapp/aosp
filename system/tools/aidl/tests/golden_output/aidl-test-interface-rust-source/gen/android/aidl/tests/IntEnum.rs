#![forbid(unsafe_code)]
#![rustfmt::skip]
#![allow(non_upper_case_globals)]
use binder::declare_binder_enum;
declare_binder_enum! {
  r#IntEnum : [i32; 4] {
    r#FOO = 1000,
    r#BAR = 2000,
    r#BAZ = 2001,
    #[deprecated = "do not use this"]
    r#QUX = 2002,
  }
}
pub(crate) mod mangled {
 pub use super::r#IntEnum as _7_android_4_aidl_5_tests_7_IntEnum;
}
