#![forbid(unsafe_code)]
#![rustfmt::skip]
#[derive(Debug)]
pub struct r#Foo {
}
impl Default for r#Foo {
  fn default() -> Self {
    Self {
    }
  }
}
impl binder::Parcelable for r#Foo {
  fn write_to_parcel(&self, parcel: &mut binder::binder_impl::BorrowedParcel) -> std::result::Result<(), binder::StatusCode> {
    parcel.sized_write(|subparcel| {
      Ok(())
    })
  }
  fn read_from_parcel(&mut self, parcel: &binder::binder_impl::BorrowedParcel) -> std::result::Result<(), binder::StatusCode> {
    parcel.sized_read(|subparcel| {
      Ok(())
    })
  }
}
binder::impl_serialize_for_parcelable!(r#Foo);
binder::impl_deserialize_for_parcelable!(r#Foo);
impl binder::binder_impl::ParcelableMetadata for r#Foo {
  fn get_descriptor() -> &'static str { "android.aidl.versioned.tests.Foo" }
}
pub(crate) mod mangled {
 pub use super::r#Foo as _7_android_4_aidl_9_versioned_5_tests_3_Foo;
}
