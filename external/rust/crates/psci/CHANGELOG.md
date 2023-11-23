# Changelog

## 0.1.3

### Bugfixes

- Fixed type of `smccc::error::success_or_error_64`. This is a breaking change relative to 0.1.2 but
  it was yanked.

## 0.1.2 (yanked)

### New features

- Added constants, types and functions for standard Arm architucture SMCCC calls, in `smccc::arch`
  module.
- Added helpers in `smccc::error` module for handling negative return values as errors.

## 0.1.1

### New features

- Exposed functions for SMC and HVC calls for use outside of PSCI.

## 0.1.0

Initial release with PSCI constants and functions.
