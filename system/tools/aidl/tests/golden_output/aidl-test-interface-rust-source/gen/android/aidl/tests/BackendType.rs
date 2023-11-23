#![forbid(unsafe_code)]
#![rustfmt::skip]
#![allow(non_upper_case_globals)]
use binder::declare_binder_enum;
declare_binder_enum! {
  r#BackendType : [i8; 4] {
    r#CPP = 0,
    r#JAVA = 1,
    r#NDK = 2,
    r#RUST = 3,
  }
}
pub(crate) mod mangled {
 pub use super::r#BackendType as _7_android_4_aidl_5_tests_11_BackendType;
}
