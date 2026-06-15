# tokenizers-rust

Rust tokenizer library wrapper providing high-performance HuggingFace tokenizers to Java via JavaCPP JNI bindings.

## Architecture

```
Rust (HuggingFace tokenizers)
  -> C++ wrapper (libtokenizers_wrapper.so/dylib/dll)
    -> JavaCPP JNI bindings
      -> Java API
```

## Modules

| Module | Purpose |
|--------|---------|
| `libtokenizers/` | C++ wrapper + Rust build scripts, HuggingFace tokenizers source |
| `tokenizers-native/` | JavaCPP binding definitions and JNI generation |
| `tokenizers-native-preset/` | Precompiled platform-specific native libraries |
| `cpp-wrapper/` | C++ bridge layer |

## Building the native library

```bash
cd tokenizers-rust/libtokenizers
./buildnativetokenizers.sh

# Build for specific platform
JAVACPP_PLATFORM=linux-x86_64 ./buildnativetokenizers.sh
```

## Supported platforms

- `linux-x86_64`
- `macosx-arm64`
- `windows-x86_64`

## Native library loading

At runtime, `NativeLibraryExtractor` copies natives from the JAR to a temp directory. `PlatformDetector` handles OS + architecture detection. Cleanup happens via shutdown hooks.

JavaCPP property `org.bytedeco.javacpp.pathsFirst=true` ensures temp-extracted libs load first.
