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
