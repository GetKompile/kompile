# Stock GraalVM Android AOT build

This directory contains the target-specific inputs for the Android graph
runtime. The build deliberately does not use Gluon or GluonFX.

The boundary is:

1. Maven compiles the graph implementation and resolves its ordinary runtime
   classpath.
2. Stock Oracle GraalVM Native Image emits an AArch64/bionic relocatable image.
   The CCompilerPath option points at the checked-in NDK compiler adapter.
3. Android NDK clang compiles the pinned Graal support sources and the pinned
   LabsJDK JNI sources.
4. NDK LLD links, versions, hardens, and strips the final shared object.
5. verify-android-ndk.sh rejects any unexpected export, dependency, host
   runtime marker, RPATH, text relocation, or non-16-KiB load segment.

This is a cross build; no Android target binary is executed except the tiny
Native Image C-ABI probes, which run as static AArch64 programs through QEMU
while the CAP cache is generated.

## Pinned inputs

- Oracle GraalVM 21.0.10+8.1, JVMCI build 23.1-b84
- Android NDK 28.1.13356709 (r28b), API 28, arm64-v8a
- Oracle Graal source vm-23.1.5,
  commit 67b6384f4502ffd46aef357d6bcfaf249b68d7d3
- LabsJDK 21 jvmci-23.1-b33,
  commit ef9d66c6808536e7029680f6f4d965359f8f20c8

The public LabsJDK repository does not publish the installed Oracle b84 source
tag. The build therefore pins the latest public 23.1 LabsJDK source used by the
successful ABI/symbol-closure build and validates every required static JNI
entry point before linking.

API 28 is intentional: the reachable Java process layer uses bionic
posix_spawn APIs introduced at Android API 28.

## Build

    ./build-android-ndk.sh \
      --android-ndk /path/to/android-sdk/ndk/28.1.13356709 \
      --graalvm-home /path/to/graalvm-jdk-21.0.10

Set BOOT_JDK_LIBRARY_PATH only when the host GraalVM needs a non-standard host
C++ runtime location during the LabsJDK build. It never enters the Android
link. Use --offline after the two pinned source checkouts and Maven artifacts
have been cached.

For a fast linker/audit iteration, pass --reuse-object, --reuse-jdk-libs, and
--reuse-svm-libs. Those options never relax the ELF audit.

## Outputs

    target/android-aot/
      jni/arm64-v8a/libkompile_reasoning_android.so
      include/kompile_reasoning.h
      include/libkompile_reasoning_android.h
      include/graal_isolate.h
      metadata/build.properties

The release library exports exactly eleven kgr_* symbols and may depend only
on Android libc, libdl, liblog, libm, and libz. There is no OpenBLAS, Fortran
runtime, host libc, Java JIT, Gluon runtime, or network fallback.

## Compatibility files

- android_compat.[ch] supplies the narrow weak bionic/JVM shims needed by the
  pinned sources. Helpers are hidden by the export map.
- labsjdk-net-util-md.patch adds the bionic socket type declaration missing
  from the upstream Unix header.
- ndk-compiler-wrapper.sh adapts Native Image C probes to NDK clang/QEMU.
- kgr_android_exports.map is an exact ABI allowlist, not a wildcard.
