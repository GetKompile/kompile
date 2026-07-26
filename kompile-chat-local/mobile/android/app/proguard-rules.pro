# ── Kompile chat core ────────────────────────────────────────────────────────
# Deliberately not kept wholesale. The app calls this layer directly, so R8's
# own reachability analysis retains everything it uses, and the core loads
# nothing by name: it has no reflection and no Java serialization. A blanket
# keep would pin desktop-only HTTP and subprocess routes into the APK. Android
# has its own guarded JNA binding to libsdx_llm for raw GGUF execution and keeps
# the JavaCPP provider path for prepared SDZ artifacts.

# ── Graph reasoning ──────────────────────────────────────────────────────────
# Also not kept wholesale. Java serialization would need it, but no graph is
# deserialized in Java here: KgraphArtifactValidator only preflights the
# container, and the graph itself is loaded by the AOT runtime inside
# libkompile_reasoning_android.so. The app touches one class, MiniJson, which
# R8 reaches on its own.

# ── JavaCPP / SDX native bindings ─────────────────────────────────────────────
# JavaCPP and JNA resolve native entry points and callback/interface methods by exact name.
-keep class org.bytedeco.javacpp.** { *; }
-keep class com.sun.jna.** { *; }
-keep class org.nd4j.dsp.runtime.** { *; }
-keep class org.eclipse.deeplearning4j.tokenizers.** { *; }

# ── Android app classes ──────────────────────────────────────────────────────
-keep class ai.kompile.chat.local.android.** { *; }

# ── SLF4J ────────────────────────────────────────────────────────────────────
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ── References that cannot resolve on Android ────────────────────────────────
# Every entry below names a type the platform genuinely does not have, reached
# only from code that never executes here. They are suppressed rather than
# supplied: adding the real artifact would ship a host-only path to a device.

# JavaCPP ships its build-time code generator in the same jar as its runtime.
# BuildMojo is a Maven plugin entry point and PointerBufferPoolMXBean registers
# a JMX bean; Android has neither Maven nor JMX, and neither runs on a device.
-dontwarn org.apache.maven.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.osgi.annotation.**

# Lombok annotations are source/class retention and are never loaded at runtime.
-dontwarn lombok.**

# StAX and Joda arrive through ND4J's shaded Jackson, which registers XML and
# Joda date modules alongside the JSON one this app actually uses.
-dontwarn javax.xml.stream.**
-dontwarn com.sun.msv.**
-dontwarn org.joda.time.**

# ProcessHandle is a Java 9 API absent from Android; ND4J's plan disk cache uses
# it only to stamp a host process id.
-dontwarn java.lang.ProcessHandle

# ── Host toolchain leakage from ND4J ─────────────────────────────────────────
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn sun.misc.**
