// build.rs — link-search path injector for Kompile Local SDK Rust example
//
// Emits cargo:rustc-link-search=native=<dir> for both libkompile_reasoning
// and libsdx_llm based on env vars or SDK layout conventions.

use std::env;
use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    // SDK layout: examples/rust/ → ../../lib/
    let sdk_lib = manifest.join("..").join("..").join("lib");

    // ── libkompile_reasoning ──────────────────────────────────────────────────
    let kgr_dir = env::var("KGR_LIB_DIR")
        .ok()
        .map(PathBuf::from)
        .unwrap_or_else(|| sdk_lib.clone());
    println!("cargo:rustc-link-search=native={}", kgr_dir.display());
    println!("cargo:rustc-link-lib=dylib=kompile_reasoning");

    // ── libsdx_llm (optional — feature-gated, but linked unconditionally here
    //    for simplicity; gracefully absent at runtime via degraded mode) ────────
    let sdx_dir = env::var("SDX_LLM_LIB_DIR")
        .ok()
        .map(PathBuf::from)
        .or_else(|| {
            env::var("SDX_LLM_AOT_HOME")
                .ok()
                .map(|h| PathBuf::from(h).join("lib"))
        })
        .unwrap_or_else(|| sdk_lib.clone());
    println!("cargo:rustc-link-search=native={}", sdx_dir.display());
    println!("cargo:rustc-link-lib=dylib=sdx_llm");

    // Re-run if env vars change
    println!("cargo:rerun-if-env-changed=KGR_LIB_DIR");
    println!("cargo:rerun-if-env-changed=SDX_LLM_LIB_DIR");
    println!("cargo:rerun-if-env-changed=SDX_LLM_AOT_HOME");
}
