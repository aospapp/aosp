// Copyright 2022, The Android Open Source Project
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

use nix::unistd::{getuid, Gid, Uid};
use rustutils::users::AID_USER_OFFSET;
use std::collections::HashSet;
use std::fmt::Write;

use android_hardware_security_keymint::aidl::android::hardware::security::keymint::SecurityLevel::SecurityLevel;
use android_system_keystore2::aidl::android::system::keystore2::{
    Domain::Domain, IKeystoreService::IKeystoreService, KeyDescriptor::KeyDescriptor,
    KeyPermission::KeyPermission, ResponseCode::ResponseCode,
};

use crate::keystore2_client_test_utils::delete_app_key;
use keystore2_test_utils::{get_keystore_service, key_generations, key_generations::Error, run_as};

/// Try to find a key with given key parameters using `listEntries` API.
fn key_alias_exists(
    keystore2: &binder::Strong<dyn IKeystoreService>,
    domain: Domain,
    nspace: i64,
    alias: String,
) -> bool {
    let key_descriptors = keystore2.listEntries(domain, nspace).unwrap();
    let alias_count = key_descriptors
        .into_iter()
        .map(|key| key.alias.unwrap())
        .filter(|key_alias| *key_alias == alias)
        .count();

    alias_count != 0
}

/// List key entries with domain as SELINUX and APP.
/// 1. Generate a key with domain as SELINUX and find this key entry in list of keys retrieved from
///    `listEntries` with domain SELINUX. Test should be able find this key entry successfully.
/// 2. Grant above generated Key to a user.
/// 3. In a user context, generate a new key with domain as APP. Try to list the key entries with
///    domain APP. Test should find only one key entry that should be the key generated in user
///    context. GRANT keys shouldn't be part of this list.
#[test]
fn keystore2_list_entries_success() {
    static GRANTOR_SU_CTX: &str = "u:r:su:s0";
    static GRANTEE_CTX: &str = "u:r:untrusted_app:s0:c91,c256,c10,c20";

    const USER_ID: u32 = 91;
    const APPLICATION_ID: u32 = 10006;
    static GRANTEE_UID: u32 = USER_ID * AID_USER_OFFSET + APPLICATION_ID;
    static GRANTEE_GID: u32 = GRANTEE_UID;

    unsafe {
        run_as::run_as(GRANTOR_SU_CTX, Uid::from_raw(0), Gid::from_raw(0), || {
            let keystore2 = get_keystore_service();
            let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();

            let alias = format!("list_entries_grant_key1_{}", getuid());

            // Make sure there is no key exist with this `alias` in `SELINUX` domain and
            // `SELINUX_SHELL_NAMESPACE` namespace.
            if key_alias_exists(
                &keystore2,
                Domain::SELINUX,
                key_generations::SELINUX_SHELL_NAMESPACE,
                alias.to_string(),
            ) {
                keystore2
                    .deleteKey(&KeyDescriptor {
                        domain: Domain::SELINUX,
                        nspace: key_generations::SELINUX_SHELL_NAMESPACE,
                        alias: Some(alias.to_string()),
                        blob: None,
                    })
                    .unwrap();
            }

            // Generate a key with above defined `alias`.
            let key_metadata = key_generations::generate_ec_p256_signing_key(
                &sec_level,
                Domain::SELINUX,
                key_generations::SELINUX_SHELL_NAMESPACE,
                Some(alias.to_string()),
                None,
            )
            .unwrap();

            // Verify that above generated key entry is listed with domain SELINUX and
            // namespace SELINUX_SHELL_NAMESPACE
            assert!(key_alias_exists(
                &keystore2,
                Domain::SELINUX,
                key_generations::SELINUX_SHELL_NAMESPACE,
                alias,
            ));

            // Grant a key with GET_INFO permission.
            let access_vector = KeyPermission::GET_INFO.0;
            keystore2
                .grant(&key_metadata.key, GRANTEE_UID.try_into().unwrap(), access_vector)
                .unwrap();
        })
    };

    // In user context validate list of key entries associated with it.
    unsafe {
        run_as::run_as(
            GRANTEE_CTX,
            Uid::from_raw(GRANTEE_UID),
            Gid::from_raw(GRANTEE_GID),
            move || {
                let keystore2 = get_keystore_service();
                let sec_level =
                    keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();
                let alias = format!("list_entries_success_key{}", getuid());

                let key_metadata = key_generations::generate_ec_p256_signing_key(
                    &sec_level,
                    Domain::APP,
                    -1,
                    Some(alias.to_string()),
                    None,
                )
                .unwrap();

                // Make sure there is only one key entry exist and that should be the same key
                // generated in this user context. Granted key shouldn't be included in this list.
                let key_descriptors = keystore2.listEntries(Domain::APP, -1).unwrap();
                assert_eq!(1, key_descriptors.len());

                let key = key_descriptors.get(0).unwrap();
                assert_eq!(key.alias, Some(alias));
                assert_eq!(key.nspace, GRANTEE_UID.try_into().unwrap());
                assert_eq!(key.domain, Domain::APP);

                keystore2.deleteKey(&key_metadata.key).unwrap();

                let key_descriptors = keystore2.listEntries(Domain::APP, -1).unwrap();
                assert_eq!(0, key_descriptors.len());
            },
        )
    };
}

/// Try to list the key entries with domain SELINUX from user context where user doesn't possesses
/// `GET_INFO` permission for specified namespace. Test should fail to list key entries with error
/// response code `PERMISSION_DENIED`.
#[test]
fn keystore2_list_entries_fails_perm_denied() {
    let auid = 91 * AID_USER_OFFSET + 10001;
    let agid = 91 * AID_USER_OFFSET + 10001;
    static TARGET_CTX: &str = "u:r:untrusted_app:s0:c91,c256,c10,c20";

    unsafe {
        run_as::run_as(TARGET_CTX, Uid::from_raw(auid), Gid::from_raw(agid), move || {
            let keystore2 = get_keystore_service();

            let result = key_generations::map_ks_error(
                keystore2.listEntries(Domain::SELINUX, key_generations::SELINUX_SHELL_NAMESPACE),
            );
            assert!(result.is_err());
            assert_eq!(Error::Rc(ResponseCode::PERMISSION_DENIED), result.unwrap_err());
        })
    };
}

/// Try to list key entries with domain BLOB. Test should fail with error repose code
/// `INVALID_ARGUMENT`.
#[test]
fn keystore2_list_entries_fails_invalid_arg() {
    let keystore2 = get_keystore_service();

    let result = key_generations::map_ks_error(
        keystore2.listEntries(Domain::BLOB, key_generations::SELINUX_SHELL_NAMESPACE),
    );
    assert!(result.is_err());
    assert_eq!(Error::Rc(ResponseCode::INVALID_ARGUMENT), result.unwrap_err());
}

/// Import large number of Keystore entries with long aliases and try to list aliases
/// of all the entries in the keystore.
#[test]
fn keystore2_list_entries_with_long_aliases_success() {
    static CLIENT_CTX: &str = "u:r:untrusted_app:s0:c91,c256,c10,c20";

    const USER_ID: u32 = 92;
    const APPLICATION_ID: u32 = 10002;
    static CLIENT_UID: u32 = USER_ID * AID_USER_OFFSET + APPLICATION_ID;
    static CLIENT_GID: u32 = CLIENT_UID;

    unsafe {
        run_as::run_as(CLIENT_CTX, Uid::from_raw(CLIENT_UID), Gid::from_raw(CLIENT_GID), || {
            let keystore2 = get_keystore_service();
            let sec_level = keystore2.getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT).unwrap();

            // Make sure there are no keystore entries exist before adding new entries.
            let key_descriptors = keystore2.listEntries(Domain::APP, -1).unwrap();
            if !key_descriptors.is_empty() {
                key_descriptors.into_iter().map(|key| key.alias.unwrap()).for_each(|alias| {
                    delete_app_key(&keystore2, &alias).unwrap();
                });
            }

            let mut imported_key_aliases = HashSet::new();

            // Import 100 keys with aliases of length 6000.
            for count in 1..101 {
                let mut alias = String::new();
                write!(alias, "{}_{}", "X".repeat(6000), count).unwrap();
                imported_key_aliases.insert(alias.clone());

                let result =
                    key_generations::import_aes_key(&sec_level, Domain::APP, -1, Some(alias));
                assert!(result.is_ok());
            }

            // b/222287335 Limiting Keystore `listEntries` API to return subset of the Keystore
            // entries to avoid running out of binder buffer space.
            // To verify that all the imported key aliases are present in Keystore,
            //  - get the list of entries from Keystore
            //  - check whether the retrieved key entries list is a subset of imported key aliases
            //  - delete this subset of keystore entries from Keystore as well as from imported
            //    list of key aliases
            //  - continue above steps till it cleanup all the imported keystore entries.
            while !imported_key_aliases.is_empty() {
                let key_descriptors = keystore2.listEntries(Domain::APP, -1).unwrap();

                // Check retrieved key entries list is a subset of imported keys list.
                assert!(key_descriptors
                    .iter()
                    .all(|key| imported_key_aliases.contains(key.alias.as_ref().unwrap())));

                // Delete the listed key entries from Keystore as well as from imported keys list.
                key_descriptors.into_iter().map(|key| key.alias.unwrap()).for_each(|alias| {
                    delete_app_key(&keystore2, &alias).unwrap();
                    assert!(imported_key_aliases.remove(&alias));
                });
            }

            assert!(imported_key_aliases.is_empty());
        })
    };
}
