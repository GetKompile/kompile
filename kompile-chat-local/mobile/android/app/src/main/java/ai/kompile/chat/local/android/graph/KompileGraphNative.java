package ai.kompile.chat.local.android.graph;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.annotation.Cast;
import org.bytedeco.javacpp.annotation.Platform;

/**
 * JavaCPP transport for the stable Kompile graph-reasoning C ABI.
 *
 * <p>This class deliberately contains no graph logic. The generated JNI wrapper
 * forwards directly into the stock-Graal/NDK {@code libkompile_reasoning_android.so}.
 * Both accelerator flavors reuse this exact transport.</p>
 */
@Platform(
        include = "kompile_reasoning.h",
        link = "kompile_reasoning_android",
        preload = "kompile_reasoning_android",
        library = "jnikompile_graph"
)
public final class KompileGraphNative {

    static {
        Loader.load();
    }

    private KompileGraphNative() {
    }

    public static native @Cast("kgr_thread_t*") Pointer kgr_create_isolate();

    public static native @Cast("kgr_thread_t*") Pointer kgr_attach_thread(
            @Cast("kgr_isolate_t*") Pointer isolate);

    public static native int kgr_detach_thread(@Cast("kgr_thread_t*") Pointer thread);

    public static native int kgr_tear_down_isolate(@Cast("kgr_thread_t*") Pointer thread);

    public static native int kgr_abi_version(@Cast("kgr_thread_t*") Pointer thread);

    public static native @Cast("kgr_session_t") long kgr_open(
            @Cast("kgr_thread_t*") Pointer thread,
            @Cast("const char*") BytePointer kgraphPath);

    public static native @Cast("const char*") BytePointer kgr_last_error(
            @Cast("kgr_thread_t*") Pointer thread);

    public static native int kgr_save(
            @Cast("kgr_thread_t*") Pointer thread,
            @Cast("kgr_session_t") long session,
            @Cast("const char*") BytePointer path);

    public static native void kgr_close(
            @Cast("kgr_thread_t*") Pointer thread,
            @Cast("kgr_session_t") long session);

    public static native @Cast("const char*") BytePointer kgr_tools(
            @Cast("kgr_thread_t*") Pointer thread);

    public static native @Cast("const char*") BytePointer kgr_dispatch(
            @Cast("kgr_thread_t*") Pointer thread,
            @Cast("kgr_session_t") long session,
            @Cast("const char*") BytePointer toolName,
            @Cast("const char*") BytePointer argsJson);

    public static native void kgr_free(
            @Cast("kgr_thread_t*") Pointer thread,
            @Cast("const char*") BytePointer result);
}
