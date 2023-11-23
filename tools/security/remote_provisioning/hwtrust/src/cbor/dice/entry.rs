use super::cose_key_from_cbor_value;
use super::field_value::FieldValue;
use crate::cbor::{cose_error, value_from_bytes};
use crate::dice::{
    ComponentVersion, ConfigDesc, ConfigDescBuilder, DiceMode, Payload, PayloadBuilder,
};
use crate::publickey::PublicKey;
use crate::session::{ComponentVersionType, ConfigFormat, ModeType, Session};
use anyhow::{anyhow, bail, Context, Result};
use ciborium::value::Value;
use coset::{AsCborValue, CoseSign1};
use std::collections::hash_map::Entry::{Occupied, Vacant};
use std::collections::HashMap;

const ISS: i64 = 1;
const SUB: i64 = 2;
const CODE_HASH: i64 = -4670545;
const CODE_DESC: i64 = -4670546;
const CONFIG_HASH: i64 = -4670547;
const CONFIG_DESC: i64 = -4670548;
const AUTHORITY_HASH: i64 = -4670549;
const AUTHORITY_DESC: i64 = -4670550;
const MODE: i64 = -4670551;
const SUBJECT_PUBLIC_KEY: i64 = -4670552;
const KEY_USAGE: i64 = -4670553;

const CONFIG_DESC_RESERVED_MAX: i64 = -70000;
const CONFIG_DESC_RESERVED_MIN: i64 = -70999;
const COMPONENT_NAME: i64 = -70002;
const COMPONENT_VERSION: i64 = -70003;
const RESETTABLE: i64 = -70004;

pub(super) struct Entry {
    payload: Vec<u8>,
}

impl Entry {
    pub(super) fn verify_cbor_value(cbor: Value, key: &PublicKey) -> Result<Self> {
        let sign1 = CoseSign1::from_cbor_value(cbor)
            .map_err(cose_error)
            .context("Given CBOR does not appear to be a COSE_sign1")?;
        key.verify_cose_sign1(&sign1).context("cannot verify COSE_sign1")?;
        match sign1.payload {
            None => bail!("Missing payload"),
            Some(payload) => Ok(Self { payload }),
        }
    }

    pub(super) fn payload(&self) -> &[u8] {
        &self.payload
    }
}

impl Payload {
    pub(super) fn from_cbor(
        session: &Session,
        bytes: &[u8],
        config_format: ConfigFormat,
    ) -> Result<Self> {
        let f = PayloadFields::from_cbor(session, bytes, config_format)?;
        PayloadBuilder::with_subject_public_key(f.subject_public_key)
            .issuer(f.issuer)
            .subject(f.subject)
            .mode(f.mode.ok_or_else(|| anyhow!("mode required"))?)
            .code_desc(f.code_desc)
            .code_hash(f.code_hash.ok_or_else(|| anyhow!("code hash required"))?)
            .config_desc(f.config_desc.ok_or_else(|| anyhow!("config desc required"))?)
            .config_hash(f.config_hash)
            .authority_desc(f.authority_desc)
            .authority_hash(f.authority_hash.ok_or_else(|| anyhow!("authority hash required"))?)
            .build()
            .context("building payload")
    }
}

pub(super) struct PayloadFields {
    pub(super) issuer: String,
    pub(super) subject: String,
    pub(super) subject_public_key: PublicKey,
    mode: Option<DiceMode>,
    code_desc: Option<Vec<u8>>,
    code_hash: Option<Vec<u8>>,
    config_desc: Option<ConfigDesc>,
    config_hash: Option<Vec<u8>>,
    authority_desc: Option<Vec<u8>>,
    authority_hash: Option<Vec<u8>>,
}

impl PayloadFields {
    pub(super) fn from_cbor(
        session: &Session,
        bytes: &[u8],
        config_format: ConfigFormat,
    ) -> Result<Self> {
        let mut issuer = FieldValue::new("issuer");
        let mut subject = FieldValue::new("subject");
        let mut subject_public_key = FieldValue::new("subject public key");
        let mut mode = FieldValue::new("mode");
        let mut code_desc = FieldValue::new("code desc");
        let mut code_hash = FieldValue::new("code hash");
        let mut config_desc = FieldValue::new("config desc");
        let mut config_hash = FieldValue::new("config hash");
        let mut authority_desc = FieldValue::new("authority desc");
        let mut authority_hash = FieldValue::new("authority hash");
        let mut key_usage = FieldValue::new("key usage");

        let entries = cbor_map_from_slice(bytes)?;
        for (key, value) in entries.into_iter() {
            if let Some(Ok(key)) = key.as_integer().map(TryInto::try_into) {
                let field = match key {
                    ISS => &mut issuer,
                    SUB => &mut subject,
                    SUBJECT_PUBLIC_KEY => &mut subject_public_key,
                    MODE => &mut mode,
                    CODE_DESC => &mut code_desc,
                    CODE_HASH => &mut code_hash,
                    CONFIG_DESC => &mut config_desc,
                    CONFIG_HASH => &mut config_hash,
                    AUTHORITY_DESC => &mut authority_desc,
                    AUTHORITY_HASH => &mut authority_hash,
                    KEY_USAGE => &mut key_usage,
                    _ => bail!("Unknown key {}", key),
                };
                field.set(value)?;
            } else {
                bail!("Invalid key: {:?}", key);
            }
        }

        validate_key_usage(session, key_usage)?;

        Ok(Self {
            issuer: issuer.into_string().context("issuer")?,
            subject: subject.into_string().context("subject")?,
            subject_public_key: validate_subject_public_key(session, subject_public_key)?,
            mode: validate_mode(session, mode).context("mode")?,
            code_desc: code_desc.into_optional_bytes().context("code descriptor")?,
            code_hash: code_hash.into_optional_bytes().context("code hash")?,
            config_desc: validate_config_desc(session, config_desc, config_format)
                .context("config descriptor")?,
            config_hash: config_hash.into_optional_bytes().context("config hash")?,
            authority_desc: authority_desc.into_optional_bytes().context("authority descriptor")?,
            authority_hash: authority_hash.into_optional_bytes().context("authority hash")?,
        })
    }
}

fn validate_key_usage(session: &Session, key_usage: FieldValue) -> Result<()> {
    let key_usage = key_usage.into_bytes().context("key usage")?;
    let key_cert_sign = 1 << 5;
    if key_usage.len() > 1
        && session.options.dice_chain_allow_big_endian_key_usage
        && key_usage[key_usage.len() - 1] == key_cert_sign
        && key_usage.iter().take(key_usage.len() - 1).all(|&x| x == 0)
    {
        return Ok(());
    }
    if key_usage.is_empty()
        || key_usage[0] != key_cert_sign
        || !key_usage.iter().skip(1).all(|&x| x == 0)
    {
        bail!("key usage must only contain keyCertSign (bit 5)");
    };
    Ok(())
}

fn validate_subject_public_key(
    session: &Session,
    subject_public_key: FieldValue,
) -> Result<PublicKey> {
    let subject_public_key = subject_public_key.into_bytes().context("Subject public")?;
    let subject_public_key = value_from_bytes(&subject_public_key).context("decode CBOR")?;
    let subject_public_key = cose_key_from_cbor_value(session, subject_public_key)
        .context("parsing subject public key")?;
    PublicKey::from_cose_key(&subject_public_key)
        .context("parsing subject public key from COSE_key")
}

fn validate_mode(session: &Session, mode: FieldValue) -> Result<Option<DiceMode>> {
    Ok(if !mode.is_bytes() && session.options.dice_chain_mode_type == ModeType::IntOrBytes {
        mode.into_optional_i64()?
    } else {
        mode.into_optional_bytes()?
            .map(|mode| {
                if mode.len() != 1 {
                    bail!("Expected mode to be a single byte, actual byte count: {}", mode.len())
                };
                Ok(mode[0].into())
            })
            .transpose()?
    }
    .map(|mode| match mode {
        1 => DiceMode::Normal,
        2 => DiceMode::Debug,
        3 => DiceMode::Recovery,
        _ => DiceMode::NotConfigured,
    }))
}

fn validate_config_desc(
    session: &Session,
    config_desc: FieldValue,
    config_format: ConfigFormat,
) -> Result<Option<ConfigDesc>> {
    let config_desc = config_desc.into_optional_bytes()?;
    config_desc
        .map(|config_desc| {
            let config =
                config_desc_from_slice(session, &config_desc).context("parsing config descriptor");
            if config.is_err() && config_format == ConfigFormat::Permissive {
                Ok(ConfigDesc::default())
            } else {
                config
            }
        })
        .transpose()
}

fn cbor_map_from_slice(bytes: &[u8]) -> Result<Vec<(Value, Value)>> {
    let value = value_from_bytes(bytes).context("Error parsing CBOR into a map")?;
    let entries = match value {
        Value::Map(entries) => entries,
        _ => bail!("Not a map: {:?}", value),
    };
    Ok(entries)
}

fn config_desc_from_slice(session: &Session, bytes: &[u8]) -> Result<ConfigDesc> {
    let entries = cbor_map_from_slice(bytes)?;

    let mut component_name = FieldValue::new("component name");
    let mut component_version = FieldValue::new("component version");
    let mut resettable = FieldValue::new("resettable");
    let mut extensions = HashMap::new();

    for (key, value) in entries.into_iter() {
        if let Some(Ok(key)) = key.as_integer().map(TryInto::try_into) {
            match key {
                COMPONENT_NAME => {
                    component_name.set(value).context("Error setting component name")?
                }
                COMPONENT_VERSION => {
                    component_version.set(value).context("Error setting component version")?
                }
                RESETTABLE => resettable.set(value).context("Error setting resettable")?,
                key if (CONFIG_DESC_RESERVED_MIN..=CONFIG_DESC_RESERVED_MAX).contains(&key) => {
                    bail!("Reserved key {}", key);
                }
                _ => match extensions.entry(key) {
                    Vacant(entry) => {
                        entry.insert(value);
                    }
                    Occupied(entry) => {
                        bail!("Duplicate values for {}: {:?} and {:?}", key, entry.get(), value)
                    }
                },
            };
        } else {
            bail!("Invalid key: {:?}", key);
        }
    }

    Ok(ConfigDescBuilder::new()
        .component_name(component_name.into_optional_string().context("Component name")?)
        .component_version(
            validate_version(session, component_version).context("Component version")?,
        )
        .resettable(resettable.is_null().context("Resettable")?)
        .build())
}

fn validate_version(session: &Session, field: FieldValue) -> Result<Option<ComponentVersion>> {
    Ok(
        if !field.is_integer()
            && session.options.dice_chain_component_version_type
                == ComponentVersionType::IntOrString
        {
            field.into_optional_string()?.map(ComponentVersion::String)
        } else {
            field.into_optional_i64()?.map(ComponentVersion::Integer)
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cbor::serialize;
    use crate::publickey::testkeys::{PrivateKey, ED25519_KEY_PEM};
    use crate::session::{KeyOpsType, Options};
    use ciborium::cbor;
    use coset::iana::{self, EnumI64};
    use coset::CborSerializable;
    use std::collections::HashMap;

    impl Entry {
        pub(in super::super) fn from_payload(payload: &Payload) -> Result<Self> {
            Ok(Self { payload: serialize(payload.to_cbor_value()?) })
        }

        pub(in super::super) fn sign(self, key: &PrivateKey) -> CoseSign1 {
            key.sign_cose_sign1(self.payload)
        }
    }

    impl Payload {
        pub(in super::super) fn to_cbor_value(&self) -> Result<Value> {
            let subject_public_key =
                self.subject_public_key().to_cose_key()?.to_vec().map_err(cose_error)?;
            let config_desc = serialize(encode_config_desc(self.config_desc()));
            let mut map = vec![
                (Value::from(ISS), Value::from(self.issuer())),
                (Value::from(SUB), Value::from(self.subject())),
                (Value::from(SUBJECT_PUBLIC_KEY), Value::from(subject_public_key)),
                (Value::from(MODE), encode_mode(self.mode())),
                (Value::from(CODE_HASH), Value::from(self.code_hash())),
                (Value::from(CONFIG_DESC), Value::from(config_desc)),
                (Value::from(AUTHORITY_HASH), Value::from(self.authority_hash())),
                (Value::from(KEY_USAGE), Value::from(vec![0x20])),
            ];
            if let Some(code_desc) = self.code_desc() {
                map.push((Value::from(CODE_DESC), Value::from(code_desc)));
            }
            if let Some(config_hash) = self.config_hash() {
                map.push((Value::from(CONFIG_HASH), Value::from(config_hash)));
            }
            if let Some(authority_desc) = self.authority_desc() {
                map.push((Value::from(AUTHORITY_DESC), Value::from(authority_desc)));
            }
            Ok(Value::Map(map))
        }
    }

    fn encode_mode(mode: DiceMode) -> Value {
        let mode = match mode {
            DiceMode::NotConfigured => 0,
            DiceMode::Normal => 1,
            DiceMode::Debug => 2,
            DiceMode::Recovery => 3,
        };
        Value::Bytes(vec![mode])
    }

    fn encode_config_desc(config_desc: &ConfigDesc) -> Value {
        let mut map = Vec::new();
        if let Some(component_name) = config_desc.component_name() {
            map.push((Value::from(COMPONENT_NAME), Value::from(component_name)));
        }
        if let Some(component_version) = config_desc.component_version() {
            map.push((
                Value::from(COMPONENT_VERSION),
                match component_version {
                    ComponentVersion::Integer(n) => Value::from(*n),
                    ComponentVersion::String(s) => Value::from(s.as_str()),
                },
            ))
        }
        if config_desc.resettable() {
            map.push((Value::from(RESETTABLE), Value::Null));
        }
        Value::Map(map)
    }

    #[test]
    fn valid_payload() {
        let fields = valid_payload_fields();
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
    }

    #[test]
    fn key_usage_only_key_cert_sign() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x20]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
    }

    #[test]
    fn key_usage_too_long() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x20, 0x30, 0x40]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_lacks_key_cert_sign() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x10]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_not_just_key_cert_sign() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x21]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn mode_not_configured() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![0]));
        let session = Session { options: Options::default() };
        let payload =
            Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::NotConfigured);
    }

    #[test]
    fn mode_normal() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![1]));
        let session = Session { options: Options::default() };
        let payload =
            Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::Normal);
    }

    #[test]
    fn mode_debug() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![2]));
        let session = Session { options: Options::default() };
        let payload =
            Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::Debug);
    }

    #[test]
    fn mode_recovery() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![3]));
        let session = Session { options: Options::default() };
        let payload =
            Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::Recovery);
    }

    #[test]
    fn mode_invalid_becomes_not_configured() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![4]));
        let session = Session { options: Options::default() };
        let payload =
            Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::NotConfigured);
    }

    #[test]
    fn mode_multiple_bytes() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::Bytes(vec![0, 1]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn mode_int_debug() {
        let mut fields = valid_payload_fields();
        fields.insert(MODE, Value::from(2));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options { dice_chain_mode_type: ModeType::IntOrBytes, ..Options::default() },
        };
        let payload = Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap();
        assert_eq!(payload.mode(), DiceMode::Debug);
    }

    #[test]
    fn subject_public_key_garbage() {
        let mut fields = valid_payload_fields();
        fields.insert(SUBJECT_PUBLIC_KEY, Value::Bytes(vec![17; 64]));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_little_endian() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x20, 0x00, 0x00]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap();
    }

    #[test]
    fn key_usage_little_endian_invalid() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x20, 0xbe, 0xef]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_big_endian() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x00, 0x20]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options { dice_chain_allow_big_endian_key_usage: true, ..Options::default() },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap();
    }

    #[test]
    fn key_usage_big_endian_invalid() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x00, 0xfe, 0x20]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options { dice_chain_allow_big_endian_key_usage: true, ..Options::default() },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_invalid() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![0x00, 0x10]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options { dice_chain_allow_big_endian_key_usage: true, ..Options::default() },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn key_usage_empty() {
        let mut fields = valid_payload_fields();
        fields.insert(KEY_USAGE, Value::Bytes(vec![]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options { dice_chain_allow_big_endian_key_usage: true, ..Options::default() },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn config_desc_custom_field_above() {
        let mut fields = valid_payload_fields();
        let config_desc = serialize(cbor!({-69999 => "custom"}).unwrap());
        fields.insert(CONFIG_DESC, Value::Bytes(config_desc));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
    }

    #[test]
    fn config_desc_reserved_field_max() {
        let mut fields = valid_payload_fields();
        let config_desc = serialize(cbor!({-70000 => "reserved"}).unwrap());
        fields.insert(CONFIG_DESC, Value::Bytes(config_desc));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn config_desc_reserved_field_min() {
        let mut fields = valid_payload_fields();
        let config_desc = serialize(cbor!({-70999 => "reserved"}).unwrap());
        fields.insert(CONFIG_DESC, Value::Bytes(config_desc));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap_err();
    }

    #[test]
    fn config_desc_custom_field_below() {
        let mut fields = valid_payload_fields();
        let config_desc = serialize(cbor!({-71000 => "custom"}).unwrap());
        fields.insert(CONFIG_DESC, Value::Bytes(config_desc));
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &serialize_fields(fields), ConfigFormat::Android).unwrap();
    }

    #[test]
    fn config_desc_not_android_spec() {
        let mut fields = valid_payload_fields();
        fields.insert(CONFIG_DESC, Value::Bytes(vec![0xcd; 64]));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let payload = Payload::from_cbor(&session, &cbor, ConfigFormat::Permissive).unwrap();
        assert_eq!(payload.config_desc(), &ConfigDesc::default());
    }

    #[test]
    fn config_desc_component_version_string() {
        let mut fields = valid_payload_fields();
        let config_desc = serialize(cbor!({COMPONENT_VERSION => "It's version 4"}).unwrap());
        fields.insert(CONFIG_DESC, Value::Bytes(config_desc));
        let cbor = serialize_fields(fields);
        let session = Session {
            options: Options {
                dice_chain_component_version_type: ComponentVersionType::Int,
                ..Options::default()
            },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session { options: Options::default() };
        let payload = Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap();
        assert_eq!(
            payload.config_desc().component_version(),
            Some(&ComponentVersion::String("It's version 4".to_string()))
        );
    }

    #[test]
    fn integer_key_ops() {
        let mut fields = valid_payload_fields();
        let subject_public_key = cbor!({
            iana::KeyParameter::Kty.to_i64() => iana::KeyType::OKP.to_i64(),
            iana::KeyParameter::Alg.to_i64() => iana::Algorithm::EdDSA.to_i64(),
            iana::KeyParameter::KeyOps.to_i64() => iana::KeyOperation::Verify.to_i64(),
            iana::OkpKeyParameter::Crv.to_i64() => iana::EllipticCurve::Ed25519.to_i64(),
            iana::OkpKeyParameter::X.to_i64() => Value::Bytes(vec![0; 32]),
        })
        .unwrap();
        fields.insert(SUBJECT_PUBLIC_KEY, Value::Bytes(serialize(subject_public_key)));
        let cbor = serialize_fields(fields);
        let session = Session { options: Options::default() };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap_err();
        let session = Session {
            options: Options {
                dice_chain_key_ops_type: KeyOpsType::IntOrArray,
                ..Options::default()
            },
        };
        Payload::from_cbor(&session, &cbor, ConfigFormat::Android).unwrap();
    }

    fn valid_payload_fields() -> HashMap<i64, Value> {
        let key = PrivateKey::from_pem(ED25519_KEY_PEM[0]).public_key();
        let subject_public_key = key.to_cose_key().unwrap().to_vec().unwrap();
        let config_desc = serialize(cbor!({COMPONENT_NAME => "component name"}).unwrap());
        HashMap::from([
            (ISS, Value::from("issuer")),
            (SUB, Value::from("subject")),
            (SUBJECT_PUBLIC_KEY, Value::Bytes(subject_public_key)),
            (KEY_USAGE, Value::Bytes(vec![0x20])),
            (CODE_HASH, Value::Bytes(vec![1; 64])),
            (CONFIG_DESC, Value::Bytes(config_desc)),
            (AUTHORITY_HASH, Value::Bytes(vec![2; 64])),
            (MODE, Value::Bytes(vec![0])),
        ])
    }

    fn serialize_fields(mut fields: HashMap<i64, Value>) -> Vec<u8> {
        let value = Value::Map(fields.drain().map(|(k, v)| (Value::from(k), v)).collect());
        serialize(value)
    }
}
