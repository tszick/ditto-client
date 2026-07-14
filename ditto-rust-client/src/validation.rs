use crate::errors::{DittoError, Result};

pub fn normalized_namespace(strict: bool, namespace: Option<&str>) -> Result<Option<String>> {
    let Some(namespace) = namespace else {
        return Ok(None);
    };
    let trimmed = namespace.trim();
    if trimmed.is_empty() {
        if strict {
            return Err(DittoError::Validation(
                "namespace must not be blank when provided".to_string(),
            ));
        }
        return Ok(None);
    }
    if strict {
        validate_namespace("request", trimmed)?;
    }
    Ok(Some(trimmed.to_string()))
}

pub fn validate_core_inputs(
    strict: bool,
    op: &str,
    key: &str,
    namespace: Option<&str>,
) -> Result<()> {
    if !strict {
        return Ok(());
    }
    if key.trim().is_empty() {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: key must not be empty"
        )));
    }
    if !is_strict_token(key) {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: key contains unsupported characters; allowed: [A-Za-z0-9._:-]"
        )));
    }
    if let Some(namespace) = namespace {
        validate_namespace(op, namespace)?;
    }
    Ok(())
}

pub fn validate_pattern_inputs(
    strict: bool,
    op: &str,
    pattern: &str,
    namespace: Option<&str>,
) -> Result<()> {
    if !strict {
        return Ok(());
    }
    let pattern = pattern.trim();
    if pattern.is_empty() {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: pattern must not be empty"
        )));
    }
    if !is_strict_pattern(pattern) {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: pattern contains unsupported characters; allowed: [A-Za-z0-9._:-*]"
        )));
    }
    if let Some(namespace) = namespace {
        validate_namespace(op, namespace)?;
    }
    Ok(())
}

fn validate_namespace(op: &str, namespace: &str) -> Result<()> {
    let namespace = namespace.trim();
    if namespace.is_empty() {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: namespace must not be blank when provided"
        )));
    }
    if namespace.contains("::") {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: namespace must not contain '::'"
        )));
    }
    if !is_strict_token(namespace) {
        return Err(DittoError::Validation(format!(
            "invalid {op} request: namespace contains unsupported characters; allowed: [A-Za-z0-9._:-]"
        )));
    }
    Ok(())
}

fn is_strict_token(value: &str) -> bool {
    value
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':'))
}

fn is_strict_pattern(value: &str) -> bool {
    value
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':' | '*'))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;
    use serde_json::Value;
    use std::fs;

    #[derive(Deserialize)]
    struct ContractSuite {
        cases: Vec<ContractCase>,
    }

    #[derive(Deserialize)]
    struct ContractCase {
        operation: String,
        inputs: serde_json::Map<String, Value>,
        expect: serde_json::Map<String, Value>,
    }

    fn err_text(result: Result<()>) -> String {
        result.unwrap_err().to_string()
    }

    #[test]
    fn normalized_namespace_handles_strict_and_lenient_blanks() {
        assert_eq!(normalized_namespace(false, None).unwrap(), None);
        assert_eq!(normalized_namespace(false, Some("   ")).unwrap(), None);
        assert_eq!(
            normalized_namespace(false, Some(" tenant-a ")).unwrap(),
            Some("tenant-a".to_string())
        );
        assert!(
            normalized_namespace(true, Some("   "))
                .unwrap_err()
                .to_string()
                .contains("namespace must not be blank")
        );
    }

    #[test]
    fn strict_core_validation_covers_key_and_namespace_failures() {
        assert!(validate_core_inputs(false, "get", "bad key", Some("bad::ns")).is_ok());
        assert!(validate_core_inputs(true, "get", "key-1._:ok", Some("tenant-a")).is_ok());
        assert!(
            err_text(validate_core_inputs(true, "get", " ", None))
                .contains("key must not be empty")
        );
        assert!(
            err_text(validate_core_inputs(true, "set", "bad key", None))
                .contains("key contains unsupported characters")
        );
        assert!(
            err_text(validate_core_inputs(true, "delete", "key", Some(" ")))
                .contains("namespace must not be blank")
        );
        assert!(
            err_text(validate_core_inputs(true, "delete", "key", Some("bad::ns")))
                .contains("namespace must not contain '::'")
        );
        assert!(
            err_text(validate_core_inputs(true, "delete", "key", Some("bad ns")))
                .contains("namespace contains unsupported characters")
        );
    }

    #[test]
    fn strict_pattern_validation_covers_pattern_and_namespace_failures() {
        assert!(
            validate_pattern_inputs(false, "deleteByPattern", "bad pattern*", Some("bad::ns"))
                .is_ok()
        );
        assert!(
            validate_pattern_inputs(true, "deleteByPattern", "tenant:*", Some("tenant-a")).is_ok()
        );
        assert!(
            err_text(validate_pattern_inputs(true, "deleteByPattern", " ", None))
                .contains("pattern must not be empty")
        );
        assert!(
            err_text(validate_pattern_inputs(
                true,
                "deleteByPattern",
                "bad pattern*",
                None
            ))
            .contains("pattern contains unsupported characters")
        );
        assert!(
            err_text(validate_pattern_inputs(
                true,
                "setTtlByPattern",
                "tenant:*",
                Some(" ")
            ))
            .contains("namespace must not be blank")
        );
        assert!(
            err_text(validate_pattern_inputs(
                true,
                "setTtlByPattern",
                "tenant:*",
                Some("bad::ns")
            ))
            .contains("namespace must not contain '::'")
        );
        assert!(
            err_text(validate_pattern_inputs(
                true,
                "setTtlByPattern",
                "tenant:*",
                Some("bad ns")
            ))
            .contains("namespace contains unsupported characters")
        );
    }

    #[test]
    fn strict_validation_contract() {
        let raw = fs::read_to_string("../contracts/strict-validation.contract.json").expect("read contract");
        let suite: ContractSuite = serde_json::from_str(&raw).expect("parse contract");

        for case in suite.cases {
            match case.operation.as_str() {
                "validate_core" => {
                    let result = validate_core_inputs(
                        case.inputs["strict_mode"].as_bool().unwrap(),
                        case.inputs["op"].as_str().unwrap(),
                        case.inputs["key"].as_str().unwrap(),
                        case.inputs.get("namespace").and_then(Value::as_str),
                    );
                    assert_validation_result(result, &case.expect);
                }
                "validate_pattern" => {
                    let result = validate_pattern_inputs(
                        case.inputs["strict_mode"].as_bool().unwrap(),
                        case.inputs["op"].as_str().unwrap(),
                        case.inputs["pattern"].as_str().unwrap(),
                        case.inputs.get("namespace").and_then(Value::as_str),
                    );
                    assert_validation_result(result, &case.expect);
                }
                "normalize_namespace" => {
                    let result = normalized_namespace(
                        case.inputs["strict_mode"].as_bool().unwrap(),
                        case.inputs.get("namespace").and_then(Value::as_str),
                    );
                    if let Some(expected_error) = case.expect.get("error_contains").and_then(Value::as_str) {
                        let err = result.unwrap_err().to_string();
                        assert!(err.contains(expected_error));
                    } else {
                        let got = result.unwrap();
                        let want = case.expect.get("normalized").and_then(Value::as_str).map(str::to_string);
                        assert_eq!(got, want);
                    }
                }
                other => panic!("unsupported contract operation: {other}"),
            }
        }
    }

    fn assert_validation_result(result: Result<()>, expect: &serde_json::Map<String, Value>) {
        if expect.get("valid").and_then(Value::as_bool).unwrap_or(false) {
            assert!(result.is_ok());
            return;
        }
        let err = result.unwrap_err().to_string();
        assert!(err.contains(expect["error_contains"].as_str().unwrap()));
    }
}
