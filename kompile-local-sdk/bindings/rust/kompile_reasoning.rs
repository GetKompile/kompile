// kompile_reasoning.rs — Safe Rust FFI wrapper for libkompile_reasoning
//
// Source: kompile/kompile-local-sdk/bindings/rust/kompile_reasoning.rs
// Mirrors the style of sdx_llm.rs (deeplearning4j/libnd4j/include/dsp/runtime/bindings/rust/src/llm.rs)
// C ABI defined in: include/kompile_reasoning.h (ABI version 1)
//
// Library resolution (build.rs must emit cargo:rustc-link-search=native=<dir>):
//   1. KGR_LIB_DIR env var (explicit directory containing libkompile_reasoning.so)
//   2. ../../lib relative to Cargo.toml (SDK zip layout)
//   3. System linker path / LD_LIBRARY_PATH
//
// Symbol scoping: Rust's #[link] is a link-time directive (not a runtime dlopen).
// The linker resolves kgr_* symbols at compile time from libkompile_reasoning.so.
// There is no RTLD_GLOBAL risk here — Rust does not call dlopen with RTLD_GLOBAL.
// libkompile_reasoning.so also applies a GNU version script (kgr_exports.lds) that
// hides graal_* / JNI_* / __svm_* from .dynsym, preventing collision with
// libsdx_llm.so in the same process even if both are dynamically linked.
//
// Threading: one kgr_thread_t per OS thread. kgr_create_isolate() creates the
// isolate and returns the calling thread's handle directly. Additional threads
// must call kgr_attach_thread(isolate_ptr) before any kgr_* call.
//
// Memory: every char* returned by kgr_tools() and kgr_dispatch() MUST be freed
// with kgr_free(). These wrappers handle that automatically.

#![allow(non_camel_case_types)]
#![allow(dead_code)]

use std::ffi::{CStr, CString};
use std::fmt;
use std::os::raw::{c_char, c_int, c_longlong, c_void};

// ── C ABI declaration ─────────────────────────────────────────────────────────

#[link(name = "kompile_reasoning")]
extern "C" {
    // Isolate lifecycle (GraalVM CREATE_ISOLATE builtin — no args, returns thread)
    fn kgr_create_isolate() -> *mut c_void;
    fn kgr_attach_thread(isolate: *mut c_void) -> *mut c_void;
    fn kgr_detach_thread(thread: *mut c_void) -> c_int;
    fn kgr_tear_down_isolate(thread: *mut c_void) -> c_int;

    // ABI version
    fn kgr_abi_version(thread: *mut c_void) -> c_int;

    // Session management
    fn kgr_open(thread: *mut c_void, kgraph_path: *const c_char) -> c_longlong;
    fn kgr_save(thread: *mut c_void, session: c_longlong, path: *const c_char) -> c_int;
    fn kgr_close(thread: *mut c_void, session: c_longlong);

    // Tool catalog + dispatch (returned char* must be freed with kgr_free)
    fn kgr_tools(thread: *mut c_void) -> *const c_char;
    fn kgr_dispatch(
        thread: *mut c_void,
        session: c_longlong,
        tool_name: *const c_char,
        args_json: *const c_char,
    ) -> *const c_char;
    fn kgr_free(thread: *mut c_void, result: *const c_char);
}

// ── Constants ─────────────────────────────────────────────────────────────────

pub const KGR_ABI_VERSION: i32 = 1;

// ── Error type ────────────────────────────────────────────────────────────────

#[derive(Debug)]
#[non_exhaustive]
pub enum KgrError {
    /// Isolate creation failed (kgr_create_isolate returned NULL).
    IsolateInit,

    /// ABI version mismatch: library returned a different version than expected.
    AbiMismatch { library: i32, expected: i32 },

    /// kgr_open returned 0 (session creation failed — bad path or I/O error).
    SessionOpen { path: String },

    /// kgr_save returned non-zero.
    SessionSave { rc: i32 },

    /// A tool dispatch returned a JSON error object.
    DispatchError { tool: String, message: String },

    /// A NUL byte was found in a string argument (CString conversion failed).
    NulByte(std::ffi::NulError),

    /// The library returned a char* that is not valid UTF-8.
    InvalidUtf8(std::str::Utf8Error),
}

impl fmt::Display for KgrError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::IsolateInit => write!(f, "kgr_create_isolate returned NULL"),
            Self::AbiMismatch { library, expected } => {
                write!(f, "KGR ABI version mismatch: library={library}, expected={expected}")
            }
            Self::SessionOpen { path } => write!(f, "kgr_open failed for path: {path}"),
            Self::SessionSave { rc } => write!(f, "kgr_save failed: rc={rc}"),
            Self::DispatchError { tool, message } => {
                write!(f, "kgr_dispatch({tool}) ERROR: {message}")
            }
            Self::NulByte(e) => write!(f, "NUL byte in argument: {e}"),
            Self::InvalidUtf8(e) => write!(f, "Invalid UTF-8 in library response: {e}"),
        }
    }
}

impl std::error::Error for KgrError {}

impl From<std::ffi::NulError> for KgrError {
    fn from(e: std::ffi::NulError) -> Self {
        Self::NulByte(e)
    }
}

// ── Internal: fetch and free a C string ──────────────────────────────────────

unsafe fn fetch_and_free(thread: *mut c_void, ptr: *const c_char) -> Result<String, KgrError> {
    if ptr.is_null() {
        return Ok(String::new());
    }
    let s = CStr::from_ptr(ptr)
        .to_str()
        .map_err(|e| KgrError::InvalidUtf8(e))?
        .to_owned();
    kgr_free(thread, ptr);
    Ok(s)
}

fn cstr(s: &str) -> Result<CString, KgrError> {
    CString::new(s).map_err(KgrError::NulByte)
}

// ── KgrSession ────────────────────────────────────────────────────────────────

/// A single open reasoning session over a `.kgraph` file.
///
/// Drop automatically closes the session. All methods must be called from the
/// same OS thread that opened the session (`thread` is not `Send`).
pub struct KgrSession {
    thread: *mut c_void, // borrowed from KgrIsolate
    id: c_longlong,
}

// Safety: kgr_* calls are synchronized by the caller (single-threaded contract).
unsafe impl Send for KgrSession {}

impl KgrSession {
    /// Return the tools catalog as a raw JSON string.
    pub fn tools_json(&self) -> Result<String, KgrError> {
        let ptr = unsafe { kgr_tools(self.thread) };
        unsafe { fetch_and_free(self.thread, ptr) }
    }

    /// Dispatch a tool call; returns the parsed JSON as a raw String.
    ///
    /// Returns `Err(KgrError::DispatchError)` if the result JSON contains
    /// `{"status":"ERROR",...}`.
    pub fn dispatch(&self, tool: &str, args_json: &str) -> Result<String, KgrError> {
        let c_tool = cstr(tool)?;
        let c_args = cstr(args_json)?;
        let ptr = unsafe {
            kgr_dispatch(self.thread, self.id, c_tool.as_ptr(), c_args.as_ptr())
        };
        let json = unsafe { fetch_and_free(self.thread, ptr) }?;
        // Check for error envelope {"status":"ERROR","message":"..."}
        if json.contains("\"ERROR\"") {
            let msg = extract_json_str(&json, "message").unwrap_or_else(|| json.clone());
            return Err(KgrError::DispatchError {
                tool: tool.to_owned(),
                message: msg,
            });
        }
        Ok(json)
    }

    /// Save the session to a `.kgraph` file.
    pub fn save(&self, path: &str) -> Result<(), KgrError> {
        let c_path = cstr(path)?;
        let rc = unsafe { kgr_save(self.thread, self.id, c_path.as_ptr()) };
        if rc != 0 {
            Err(KgrError::SessionSave { rc })
        } else {
            Ok(())
        }
    }
}

impl Drop for KgrSession {
    fn drop(&mut self) {
        if self.id != 0 {
            unsafe { kgr_close(self.thread, self.id) };
        }
    }
}

// ── KgrIsolate ───────────────────────────────────────────────────────────────

/// GraalVM isolate + its primary thread handle.
///
/// One per process is the typical pattern. Create with `KgrIsolate::new()`.
/// Drop tears down the isolate and invalidates all session handles.
pub struct KgrIsolate {
    thread: *mut c_void,
}

// Safety: single-threaded contract; callers must not share thread across threads.
unsafe impl Send for KgrIsolate {}

impl KgrIsolate {
    /// Create a new isolate. Performs ABI version check on success.
    pub fn new() -> Result<Self, KgrError> {
        let thread = unsafe { kgr_create_isolate() };
        if thread.is_null() {
            return Err(KgrError::IsolateInit);
        }
        let abi = unsafe { kgr_abi_version(thread) };
        if abi != KGR_ABI_VERSION {
            unsafe { kgr_tear_down_isolate(thread) };
            return Err(KgrError::AbiMismatch {
                library: abi,
                expected: KGR_ABI_VERSION,
            });
        }
        Ok(Self { thread })
    }

    /// Return the ABI version reported by the loaded library.
    pub fn abi_version(&self) -> i32 {
        unsafe { kgr_abi_version(self.thread) }
    }

    /// Open a `.kgraph` file and return a session.
    ///
    /// Pass `None` to create an empty session (useful for building graphs via
    /// `ask_graph_assert` dispatch calls).
    pub fn open(&self, kgraph_path: Option<&str>) -> Result<KgrSession, KgrError> {
        let id = match kgraph_path {
            Some(p) => {
                let c_path = cstr(p)?;
                unsafe { kgr_open(self.thread, c_path.as_ptr()) }
            }
            None => unsafe { kgr_open(self.thread, std::ptr::null()) },
        };
        if id == 0 {
            Err(KgrError::SessionOpen {
                path: kgraph_path.unwrap_or("(null)").to_owned(),
            })
        } else {
            Ok(KgrSession { thread: self.thread, id })
        }
    }
}

impl Drop for KgrIsolate {
    fn drop(&mut self) {
        if !self.thread.is_null() {
            unsafe { kgr_tear_down_isolate(self.thread) };
        }
    }
}

// ── Minimal JSON string extractor (no external deps) ─────────────────────────

fn extract_json_str(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\"", key);
    let start = json.find(&needle)?;
    let after_key = &json[start + needle.len()..];
    let colon = after_key.find(':')? + 1;
    let value_part = after_key[colon..].trim_start();
    if value_part.starts_with('"') {
        let inner = &value_part[1..];
        let end = inner.find('"')?;
        Some(inner[..end].to_owned())
    } else {
        None
    }
}
