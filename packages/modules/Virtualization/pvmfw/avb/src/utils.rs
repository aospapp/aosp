// Copyright 2023, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Common utility functions.

use crate::error::AvbIOError;
use core::ptr::NonNull;
use core::result;

pub(crate) type Result<T> = result::Result<T, AvbIOError>;

pub(crate) fn write<T>(ptr: *mut T, value: T) -> Result<()> {
    let ptr = to_nonnull(ptr)?;
    // SAFETY: It is safe as the raw pointer `ptr` is a non-null pointer.
    unsafe {
        *ptr.as_ptr() = value;
    }
    Ok(())
}

pub(crate) fn as_ref<'a, T>(ptr: *mut T) -> Result<&'a T> {
    let ptr = to_nonnull(ptr)?;
    // SAFETY: It is safe as the raw pointer `ptr` is a non-null pointer.
    unsafe { Ok(ptr.as_ref()) }
}

pub(crate) fn to_nonnull<T>(ptr: *mut T) -> Result<NonNull<T>> {
    NonNull::new(ptr).ok_or(AvbIOError::NoSuchValue)
}

pub(crate) fn is_not_null<T>(ptr: *const T) -> Result<()> {
    if ptr.is_null() {
        Err(AvbIOError::NoSuchValue)
    } else {
        Ok(())
    }
}

pub(crate) fn to_usize<T: TryInto<usize>>(num: T) -> Result<usize> {
    num.try_into().map_err(|_| AvbIOError::InvalidValueSize)
}

pub(crate) fn usize_checked_add(x: usize, y: usize) -> Result<usize> {
    x.checked_add(y).ok_or(AvbIOError::InvalidValueSize)
}
