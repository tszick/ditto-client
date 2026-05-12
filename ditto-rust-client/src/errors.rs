use std::fmt;

pub type Result<T> = std::result::Result<T, DittoError>;

#[derive(Debug)]
pub enum DittoError {
    Server { code: String, message: String },
    Validation(String),
    Protocol(String),
    Http(reqwest::Error),
    Io(std::io::Error),
    Json(serde_json::Error),
}

impl DittoError {
    pub fn server(code: impl Into<String>, message: impl Into<String>) -> Self {
        Self::Server {
            code: code.into(),
            message: message.into(),
        }
    }
}

impl fmt::Display for DittoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Server { code, message } => write!(f, "{code}: {message}"),
            Self::Validation(message) => write!(f, "validation error: {message}"),
            Self::Protocol(message) => write!(f, "protocol error: {message}"),
            Self::Http(err) => write!(f, "http error: {err}"),
            Self::Io(err) => write!(f, "io error: {err}"),
            Self::Json(err) => write!(f, "json error: {err}"),
        }
    }
}

impl std::error::Error for DittoError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Http(err) => Some(err),
            Self::Io(err) => Some(err),
            Self::Json(err) => Some(err),
            _ => None,
        }
    }
}

impl From<reqwest::Error> for DittoError {
    fn from(err: reqwest::Error) -> Self {
        Self::Http(err)
    }
}

impl From<std::io::Error> for DittoError {
    fn from(err: std::io::Error) -> Self {
        Self::Io(err)
    }
}

impl From<serde_json::Error> for DittoError {
    fn from(err: serde_json::Error) -> Self {
        Self::Json(err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::error::Error;

    #[test]
    fn display_formats_all_error_variants() {
        assert_eq!(
            DittoError::server("RateLimited", "slow down").to_string(),
            "RateLimited: slow down"
        );
        assert_eq!(
            DittoError::Validation("bad key".to_string()).to_string(),
            "validation error: bad key"
        );
        assert_eq!(
            DittoError::Protocol("bad frame".to_string()).to_string(),
            "protocol error: bad frame"
        );
        assert!(
            std::io::Error::from(std::io::ErrorKind::TimedOut)
                .to_string()
                .contains("timed out")
        );
    }

    #[test]
    fn source_is_present_for_wrapped_errors_only() {
        let io_error = DittoError::from(std::io::Error::from(std::io::ErrorKind::UnexpectedEof));
        assert!(io_error.source().is_some());

        let json_error =
            DittoError::from(serde_json::from_str::<serde_json::Value>("{").unwrap_err());
        assert!(json_error.source().is_some());

        assert!(DittoError::server("InternalError", "x").source().is_none());
        assert!(DittoError::Validation("x".to_string()).source().is_none());
        assert!(DittoError::Protocol("x".to_string()).source().is_none());
    }
}
