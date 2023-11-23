#![forbid(unsafe_code)]
#![rustfmt::skip]
#![allow(non_upper_case_globals)]
use binder::declare_binder_enum;
declare_binder_enum! {
  #[deprecated = "test"]
  r#DeprecatedEnum : [i32; 3] {
    r#A = 0,
    r#B = 1,
    r#C = 2,
  }
}
pub(crate) mod mangled {
 pub use super::r#DeprecatedEnum as _7_android_4_aidl_5_tests_14_DeprecatedEnum;
}
