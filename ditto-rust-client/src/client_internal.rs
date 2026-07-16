use reqwest::StatusCode;
use serde::Deserialize;

use crate::errors::DittoError;

pub(crate) fn namespace_header_value(namespace: Option<&str>) -> Option<&str> {
    namespace.filter(|ns| !ns.trim().is_empty()).map(str::trim)
}

pub(crate) fn is_atomic_unsupported_status(status: StatusCode) -> bool {
    matches!(status.as_u16(), 400 | 404 | 501)
}

pub(crate) fn atomic_http_unsupported_error_body(body: &[u8], operation: &str) -> DittoError {
    let payload = serde_json::from_slice::<ErrorResponse>(body).ok();
    if payload.as_ref().and_then(|p| p.error.as_deref()) == Some("UnsupportedRequest") {
        let message = payload
            .and_then(|p| p.message)
            .unwrap_or_else(|| "UnsupportedRequest".to_string());
        return DittoError::server("UnsupportedRequest", message);
    }
    unsupported_atomic_operation_error(operation)
}

pub(crate) fn normalize_atomic_tcp_error(error: DittoError, operation: &str) -> DittoError {
    if matches!(error, DittoError::Server { .. }) {
        return error;
    }
    let normalized = error.to_string().to_lowercase();
    if normalized.contains("request outcome unknown") {
        return error;
    }
    if normalized.contains("unsupported")
        || normalized.contains("protocol")
        || normalized.contains("decode")
        || normalized.contains("unexpected response")
        || normalized.contains("eof")
        || normalized.contains("connection reset")
    {
        return unsupported_atomic_operation_error(operation);
    }
    error
}

pub(crate) fn unsupported_atomic_operation_error(operation: &str) -> DittoError {
    DittoError::server(
        "UnsupportedRequest",
        format!(
            "UnsupportedRequest: server does not support {operation}. Upgrade dittod to a version with atomic primitives."
        ),
    )
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: Option<String>,
    message: Option<String>,
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

    #[test]
    fn atomic_errors_contract() {
        let raw =
            fs::read_to_string("../contracts/atomic-errors.contract.json").expect("read contract");
        let suite: ContractSuite = serde_json::from_str(&raw).expect("parse contract");

        for case in suite.cases {
            let err = match case.operation.as_str() {
                "normalize_http_atomic_error" => atomic_http_unsupported_error_body(
                    case.inputs["body"].as_str().unwrap().as_bytes(),
                    case.inputs["operation_name"].as_str().unwrap(),
                ),
                "normalize_tcp_atomic_error" => {
                    let input = match case.inputs["error_kind"].as_str().unwrap() {
                        "ditto" => DittoError::server(
                            case.inputs["error_code"].as_str().unwrap(),
                            case.inputs["error_message"].as_str().unwrap(),
                        ),
                        _ => DittoError::Protocol(
                            case.inputs["error_message"].as_str().unwrap().to_string(),
                        ),
                    };
                    normalize_atomic_tcp_error(
                        input,
                        case.inputs["operation_name"].as_str().unwrap(),
                    )
                }
                other => panic!("unsupported contract operation: {other}"),
            };
            match err {
                DittoError::Server { code, message } => {
                    assert_eq!(code, case.expect["code"].as_str().unwrap());
                    assert!(message.contains(case.expect["message_contains"].as_str().unwrap()));
                }
                other => panic!("unexpected error variant: {other:?}"),
            }
        }
    }
}
