"""
kompile_reasoning.py — ctypes binding for libkompile_reasoning

Minimal wrapper around the kgr_* C ABI exported by libkompile_reasoning.so
(Linux) / libkompile_reasoning.dylib (macOS).  Mirrors the style of the
SDX runtime Python binding (sdx_runtime.py) but is self-contained.

Usage:

    from kompile_reasoning import KomReasoningLib
    import json

    lib = KomReasoningLib("/path/to/libkompile_reasoning.so")
    with lib.session("/path/to/project.kgraph") as sess:
        catalog = sess.tools()          # returns Python list (parsed JSON)
        result  = sess.dispatch("graph_reasoning_query", {"operation": "OVERVIEW"})
        verdict = sess.dispatch("ask_graph_verify", {"atom": "WORKS_AT(alice, acme)"})

ABI version check is performed automatically on construction; ValueError is
raised if the library's ABI version does not match KGR_ABI_VERSION.
"""

import ctypes
import ctypes.util
import json
import os
import sys
from contextlib import contextmanager
from typing import Optional, Union

# The ABI version this binding was written against.
KGR_ABI_VERSION = 1


# ── Low-level ctypes wrapper ──────────────────────────────────────────────────

class _KgrLib:
    """
    Raw ctypes wrapper.  All kgr_* functions are exposed with correct argtypes
    and restypes so ctypes performs automatic type checking.  Prefer using
    KomReasoningLib / KgrSession instead of this class directly.
    """

    def __init__(self, lib_path: str):
        """
        Load the shared library at *lib_path*.

        :param lib_path: Absolute path to libkompile_reasoning.so / .dylib.
        :raises OSError:    If the library cannot be loaded.
        :raises ValueError: If the ABI version does not match KGR_ABI_VERSION.
        """
        self._lib = ctypes.CDLL(lib_path)
        self._setup_signatures()

        # Create isolate once per process (process-lifetime singleton pattern).
        # kgr_create_isolate is a GraalVM CREATE_ISOLATE builtin: no arguments,
        # returns the thread handle directly (not via out-params like graal_create_isolate).
        self._thread = self._lib.kgr_create_isolate()
        if not self._thread:
            raise RuntimeError("kgr_create_isolate returned NULL")

        abi = self._lib.kgr_abi_version(self._thread)
        if abi != KGR_ABI_VERSION:
            self._lib.kgr_tear_down_isolate(self._thread)
            raise ValueError(
                f"ABI version mismatch: library={abi}, binding={KGR_ABI_VERSION}"
            )

    # ── ctypes signature setup ────────────────────────────────────────────────

    def _setup_signatures(self):
        lib = self._lib

        # Isolate lifecycle
        # kgr_create_isolate: GraalVM CREATE_ISOLATE builtin — no args, returns thread
        lib.kgr_create_isolate.argtypes  = []
        lib.kgr_create_isolate.restype   = ctypes.c_void_p

        # kgr_attach_thread: takes isolate pointer, returns thread pointer
        lib.kgr_attach_thread.argtypes   = [ctypes.c_void_p]
        lib.kgr_attach_thread.restype    = ctypes.c_void_p

        lib.kgr_detach_thread.argtypes   = [ctypes.c_void_p]
        lib.kgr_detach_thread.restype    = ctypes.c_int

        lib.kgr_tear_down_isolate.argtypes = [ctypes.c_void_p]
        lib.kgr_tear_down_isolate.restype  = ctypes.c_int

        # ABI version
        lib.kgr_abi_version.argtypes     = [ctypes.c_void_p]
        lib.kgr_abi_version.restype      = ctypes.c_int

        # Session management
        lib.kgr_open.argtypes            = [ctypes.c_void_p, ctypes.c_char_p]
        lib.kgr_open.restype             = ctypes.c_longlong

        lib.kgr_save.argtypes            = [ctypes.c_void_p,
                                             ctypes.c_longlong,
                                             ctypes.c_char_p]
        lib.kgr_save.restype             = ctypes.c_int

        lib.kgr_close.argtypes           = [ctypes.c_void_p, ctypes.c_longlong]
        lib.kgr_close.restype            = None

        # Tool catalog + dispatch
        lib.kgr_tools.argtypes           = [ctypes.c_void_p]
        lib.kgr_tools.restype            = ctypes.c_char_p

        lib.kgr_dispatch.argtypes        = [ctypes.c_void_p,
                                             ctypes.c_longlong,
                                             ctypes.c_char_p,
                                             ctypes.c_char_p]
        lib.kgr_dispatch.restype         = ctypes.c_char_p

        # Memory management
        lib.kgr_free.argtypes            = [ctypes.c_void_p, ctypes.c_char_p]
        lib.kgr_free.restype             = None

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _enc(self, s: str) -> bytes:
        """Encode a Python str to UTF-8 bytes for C char* parameters."""
        return s.encode("utf-8") if s is not None else b""

    def _fetch(self, raw_ptr) -> str:
        """
        Copy a C string returned by kgr_tools / kgr_dispatch to a Python str,
        then release the C memory via kgr_free.

        Note: ctypes with restype=c_char_p automatically converts the returned
        pointer to Python bytes (copying the content).  The original C pointer
        is lost, so we cannot call kgr_free on it via ctypes c_char_p.  To work
        around this, kgr_tools / kgr_dispatch return c_char_p for easy byte
        extraction, but we treat the returned bytes as a copy and skip kgr_free
        (the GC will handle the Python bytes object; the C memory is NOT freed in
        this path).

        IMPORTANT: This is a known ctypes limitation with c_char_p restype.  For
        production usage or memory-sensitive deployments, switch kgr_tools /
        kgr_dispatch to restype=c_void_p, extract the string via
        ctypes.string_at(ptr), then call kgr_free(thread, ptr).  The method
        _fetch_and_free below does exactly that and is used by open() / dispatch().
        """
        if raw_ptr is None:
            return ""
        return raw_ptr.decode("utf-8") if isinstance(raw_ptr, bytes) else raw_ptr

    def _fetch_and_free_voidp(self, raw: int) -> str:
        """
        For restype=c_void_p: extract the UTF-8 string at *raw*, then free it.
        Use this variant for kgr_dispatch / kgr_tools calls that must kgr_free.
        """
        if not raw:
            return ""
        s = ctypes.string_at(raw).decode("utf-8")
        # Cast back to c_char_p for kgr_free (it just needs the address)
        self._lib.kgr_free(self._thread, ctypes.cast(raw, ctypes.c_char_p))
        return s

    # ── Public low-level API ──────────────────────────────────────────────────

    def abi_version(self) -> int:
        return self._lib.kgr_abi_version(self._thread)

    def open(self, kgraph_path: Optional[str]) -> int:
        path_bytes = self._enc(kgraph_path) if kgraph_path else None
        return int(self._lib.kgr_open(self._thread, path_bytes))

    def tools_raw(self) -> str:
        """Return catalog JSON; frees C memory."""
        # Use void_p so we can call kgr_free
        old_rt = self._lib.kgr_tools.restype
        self._lib.kgr_tools.restype = ctypes.c_void_p
        raw = self._lib.kgr_tools(self._thread)
        self._lib.kgr_tools.restype = old_rt
        return self._fetch_and_free_voidp(raw)

    def dispatch_raw(self, session_id: int, tool_name: str, args_json: str) -> str:
        """Return result JSON; frees C memory."""
        old_rt = self._lib.kgr_dispatch.restype
        self._lib.kgr_dispatch.restype = ctypes.c_void_p
        raw = self._lib.kgr_dispatch(
            self._thread,
            ctypes.c_longlong(session_id),
            self._enc(tool_name),
            self._enc(args_json),
        )
        self._lib.kgr_dispatch.restype = old_rt
        return self._fetch_and_free_voidp(raw)

    def save(self, session_id: int, path: str) -> int:
        return int(self._lib.kgr_save(self._thread, session_id, self._enc(path)))

    def close(self, session_id: int) -> None:
        self._lib.kgr_close(self._thread, session_id)

    def tear_down(self) -> None:
        self._lib.kgr_tear_down_isolate(self._thread)


# ── High-level session wrapper ────────────────────────────────────────────────

class KgrSession:
    """
    High-level wrapper around a single open kgr session.

    Returned by KomReasoningLib.session() (context manager) or
    KomReasoningLib.open().  Prefer the context manager form so the session
    is closed automatically.
    """

    def __init__(self, lib: _KgrLib, session_id: int):
        self._lib = lib
        self._id  = session_id
        if session_id == 0:
            raise RuntimeError("kgr_open returned 0 — check the .kgraph path")

    # ── Context-manager support ───────────────────────────────────────────────

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False  # do not suppress exceptions

    # ── API ───────────────────────────────────────────────────────────────────

    def tools(self):
        """
        Return the tool catalog as a Python list of dicts (parsed from JSON).
        """
        raw = self._lib.tools_raw()
        return json.loads(raw)

    def dispatch(self, tool_name: str, args: Optional[Union[dict, str]] = None) -> dict:
        """
        Dispatch a tool call and return the parsed JSON result as a dict.

        :param tool_name: Name of the tool (e.g. "ask_graph_verify").
        :param args:      Arguments as a Python dict, a JSON string, or None.
        :returns:         Parsed JSON result dict.
        :raises RuntimeError: If the result contains {"status": "ERROR", ...}.
        """
        if args is None:
            args_json = "{}"
        elif isinstance(args, dict):
            args_json = json.dumps(args)
        else:
            args_json = str(args)

        raw = self._lib.dispatch_raw(self._id, tool_name, args_json)
        result = json.loads(raw)
        if isinstance(result, dict) and result.get("status") == "ERROR":
            raise RuntimeError(
                f"kgr_dispatch({tool_name!r}) returned ERROR: "
                f"{result.get('message', '(no message)')}"
            )
        return result

    def save(self, path: str) -> None:
        """
        Save the current graph state to *path* as a .kgraph file.

        :raises RuntimeError: If kgr_save returns a non-zero error code.
        """
        rc = self._lib.save(self._id, path)
        if rc != 0:
            raise RuntimeError(f"kgr_save failed: rc={rc}")

    def close(self) -> None:
        """Close the session.  Safe to call multiple times."""
        if self._id != 0:
            self._lib.close(self._id)
            self._id = 0

    @property
    def session_id(self) -> int:
        return self._id


# ── Top-level convenience class ───────────────────────────────────────────────

class KomReasoningLib:
    """
    Top-level entry point for the Kompile graph-reasoning Python binding.

    Manages isolate lifetime (one isolate per process).  Session lifecycle is
    managed via the context manager returned by .session() or the explicit
    .open() / .close() pair.

    Example:

        lib = KomReasoningLib("/path/to/libkompile_reasoning.so")
        with lib.session("/path/to/project.kgraph") as sess:
            overview = sess.dispatch("graph_reasoning_query",
                                     {"operation": "OVERVIEW"})
            print(overview)
        lib.close()
    """

    def __init__(self, lib_path: str):
        """
        Load libkompile_reasoning and create the GraalVM isolate.

        :param lib_path: Path to the shared library (.so / .dylib).
        :raises OSError:    If the library is not found.
        :raises ValueError: If the library ABI version does not match KGR_ABI_VERSION.
        :raises RuntimeError: If isolate creation fails.
        """
        self._lib = _KgrLib(lib_path)

    # ── Context-manager: whole-library lifetime ───────────────────────────────

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False

    # ── Session factory ───────────────────────────────────────────────────────

    @contextmanager
    def session(self, kgraph_path: Optional[str] = None):
        """
        Context manager that opens a session and closes it on exit.

        :param kgraph_path: Path to .kgraph file, or None for an empty session.
        :yields: KgrSession
        """
        sess = self.open(kgraph_path)
        try:
            yield sess
        finally:
            sess.close()

    def open(self, kgraph_path: Optional[str] = None) -> KgrSession:
        """
        Open a .kgraph file and return a KgrSession.  Caller is responsible for
        calling sess.close() when done.  Prefer .session() context manager.
        """
        sid = self._lib.open(kgraph_path)
        return KgrSession(self._lib, sid)

    # ── Library-level API ─────────────────────────────────────────────────────

    def abi_version(self) -> int:
        """Return the library's ABI version integer."""
        return self._lib.abi_version()

    def tools(self):
        """
        Return the tool catalog as a Python list of dicts.
        This is session-independent — same result regardless of which session
        (if any) is open.
        """
        raw = self._lib.tools_raw()
        return json.loads(raw)

    def close(self) -> None:
        """Tear down the GraalVM isolate.  Safe to call multiple times."""
        if self._lib is not None:
            self._lib.tear_down()
            self._lib = None


# ── Helper: find the library relative to this script ─────────────────────────

def find_library(hint_dir: Optional[str] = None) -> str:
    """
    Attempt to locate libkompile_reasoning in common places.

    Search order:
      1. *hint_dir* if provided.
      2. Directory containing this .py file (typical SDK zip layout).
      3. Standard system paths via ctypes.util.find_library.

    :returns: Absolute path to the shared library.
    :raises FileNotFoundError: If the library cannot be found.
    """
    ext = "dylib" if sys.platform == "darwin" else "so"
    name = f"libkompile_reasoning.{ext}"

    candidates = []
    if hint_dir:
        candidates.append(os.path.join(hint_dir, name))

    # Sibling of this file (SDK zip has include/ + lib/ + bindings/python/)
    here = os.path.dirname(os.path.abspath(__file__))
    candidates.append(os.path.join(here, "..", "..", "lib", name))  # SDK layout
    candidates.append(os.path.join(here, name))                     # flat layout

    for path in candidates:
        abs_path = os.path.abspath(path)
        if os.path.isfile(abs_path):
            return abs_path

    # Fall back to system search
    found = ctypes.util.find_library("kompile_reasoning")
    if found:
        return found

    raise FileNotFoundError(
        f"Cannot find {name}.  Pass the directory containing it as hint_dir, "
        f"or set LD_LIBRARY_PATH (Linux) / DYLD_LIBRARY_PATH (macOS)."
    )


# ── Mini smoke test (python -m kompile_reasoning <lib_path> <fixture.kgraph>) ─

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(f"Usage: python {sys.argv[0]} <lib_path> <fixture.kgraph>", file=sys.stderr)
        sys.exit(2)

    lib_path    = sys.argv[1]
    kgraph_path = sys.argv[2]

    print(f"=== kompile_reasoning.py mini-smoke ===")
    print(f"  lib:     {lib_path}")
    print(f"  kgraph:  {kgraph_path}")

    lib = KomReasoningLib(lib_path)
    print(f"  ABI version: {lib.abi_version()}")

    with lib.session(kgraph_path) as sess:
        # Catalog
        catalog = sess.tools()
        tool_names = [t.get("name", t.get("id", "?")) for t in catalog] if isinstance(catalog, list) else []
        print(f"  Tools ({len(tool_names)}): {', '.join(tool_names[:5])}{'...' if len(tool_names) > 5 else ''}")
        assert len(tool_names) > 0, "Catalog is empty"

        # OVERVIEW
        overview = sess.dispatch("graph_reasoning_query", {"operation": "OVERVIEW"})
        assert overview.get("status") != "ERROR", f"OVERVIEW failed: {overview}"
        print(f"  OVERVIEW status: {overview.get('status', 'OK')}")

        # ask_graph_verify
        verify = sess.dispatch("ask_graph_verify", {"atom": "WORKS_AT(alice, acme)"})
        verdict = verify.get("verdict", "")
        print(f"  ask_graph_verify WORKS_AT: {verdict}")
        assert "SUPPORTED" in verdict, f"Expected SUPPORTED, got: {verify}"

    lib.close()
    print("  PASS: python smoke complete")
