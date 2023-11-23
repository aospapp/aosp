#![forbid(unsafe_code)]
#![rustfmt::skip]
#[derive(Debug)]
pub enum r#BazUnion {
  IntNum(i32),
}
impl Default for r#BazUnion {
  fn default() -> Self {
    Self::IntNum(0)
  }
}
impl binder::Parcelable for r#BazUnion {
  fn write_to_parcel(&self, parcel: &mut binder::binder_impl::BorrowedParcel) -> std::result::Result<(), binder::StatusCode> {
    match self {
      Self::IntNum(v) => {
        parcel.write(&0i32)?;
        parcel.write(v)
      }
    }
  }
  fn read_from_parcel(&mut self, parcel: &binder::binder_impl::BorrowedParcel) -> std::result::Result<(), binder::StatusCode> {
    let tag: i32 = parcel.read()?;
    match tag {
      0 => {
        let value: i32 = parcel.read()?;
        *self = Self::IntNum(value);
        Ok(())
      }
      _ => {
        Err(binder::StatusCode::BAD_VALUE)
      }
    }
  }
}
binder::impl_serialize_for_parcelable!(r#BazUnion);
binder::impl_deserialize_for_parcelable!(r#BazUnion);
impl binder::binder_impl::ParcelableMetadata for r#BazUnion {
  fn get_descriptor() -> &'static str { "android.aidl.versioned.tests.BazUnion" }
}
pub mod r#Tag {
  #![allow(non_upper_case_globals)]
  use binder::declare_binder_enum;
  declare_binder_enum! {
    r#Tag : [i32; 1] {
      r#intNum = 0,
    }
  }
}
pub(crate) mod mangled {
 pub use super::r#BazUnion as _7_android_4_aidl_9_versioned_5_tests_8_BazUnion;
 pub use super::r#Tag::r#Tag as _7_android_4_aidl_9_versioned_5_tests_8_BazUnion_3_Tag;
}
