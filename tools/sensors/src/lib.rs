//! Shared library for kotlin-analyzer sensors and integration tests.
//!
//! Hosts the [`lsp_client::LspTestClient`] that drives the real
//! `kotlin-analyzer` LSP binary over stdin/stdout, and the
//! [`completion_burst`] sensor that exercises the bridge under typing-burst
//! load to validate the latest-wins supersession behaviour from ka-rr3u.

pub mod completion_burst;
pub mod lsp_client;
