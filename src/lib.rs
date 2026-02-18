use zed_extension_api::{self as zed};

struct KotlinAnalyzerExtension;

impl zed::Extension for KotlinAnalyzerExtension {
    fn new() -> Self {
        eprintln!("kotlin-analyzer: extension initialized");
        Self
    }
}

zed::register_extension!(KotlinAnalyzerExtension);
