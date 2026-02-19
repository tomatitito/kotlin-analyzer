use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    pub java_home: Option<String>,
    pub compiler_flags: Vec<String>,
    pub formatting_tool: FormattingTool,
    pub formatting_style: String,
    pub sidecar_max_memory: String,
    pub trace_server: TraceLevel,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            java_home: None,
            compiler_flags: Vec::new(),
            formatting_tool: FormattingTool::Ktfmt,
            formatting_style: "google".into(),
            sidecar_max_memory: "512m".into(),
            trace_server: TraceLevel::Off,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum FormattingTool {
    Ktfmt,
    Ktlint,
    None,
}

impl Default for FormattingTool {
    fn default() -> Self {
        Self::Ktfmt
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum TraceLevel {
    Off,
    Messages,
    Verbose,
}

impl Default for TraceLevel {
    fn default() -> Self {
        Self::Off
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = Config::default();
        assert!(config.java_home.is_none());
        assert!(config.compiler_flags.is_empty());
        assert_eq!(config.formatting_tool, FormattingTool::Ktfmt);
        assert_eq!(config.formatting_style, "google");
        assert_eq!(config.sidecar_max_memory, "512m");
        assert_eq!(config.trace_server, TraceLevel::Off);
    }

    #[test]
    fn test_parse_config_with_all_fields() {
        let json = r#"{
            "javaHome": "/usr/lib/jvm/java-17",
            "compilerFlags": ["-Xcontext-parameters"],
            "formattingTool": "ktlint",
            "formattingStyle": "android",
            "sidecarMaxMemory": "1g",
            "traceServer": "verbose"
        }"#;
        let config: Config = serde_json::from_str(json).unwrap();
        assert_eq!(config.java_home, Some("/usr/lib/jvm/java-17".into()));
        assert_eq!(config.compiler_flags, vec!["-Xcontext-parameters"]);
        assert_eq!(config.formatting_tool, FormattingTool::Ktlint);
        assert_eq!(config.formatting_style, "android");
        assert_eq!(config.sidecar_max_memory, "1g");
        assert_eq!(config.trace_server, TraceLevel::Verbose);
    }

    #[test]
    fn test_parse_config_with_missing_fields() {
        let json = r#"{"javaHome": "/usr/lib/jvm/java-17"}"#;
        let config: Config = serde_json::from_str(json).unwrap();
        assert_eq!(config.java_home, Some("/usr/lib/jvm/java-17".into()));
        assert!(config.compiler_flags.is_empty());
        assert_eq!(config.formatting_tool, FormattingTool::Ktfmt);
    }

    #[test]
    fn test_parse_empty_config() {
        let json = "{}";
        let config: Config = serde_json::from_str(json).unwrap();
        assert!(config.java_home.is_none());
    }
}
