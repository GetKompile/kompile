# ── Kompile chat core ────────────────────────────────────────────────────────
# Keep all public API of the chat core so reflection from the reasoning lib works.
-keep class ai.kompile.chat.local.** { *; }
-keep class ai.kompile.graph.reasoning.** { *; }

# ── JavaCPP / SDX native bindings ─────────────────────────────────────────────
# JavaCPP resolves generated JNI entry points and callback classes by exact name.
-keep class org.bytedeco.javacpp.** { *; }
-keep class org.nd4j.dsp.runtime.** { *; }
-keep class org.eclipse.deeplearning4j.tokenizers.** { *; }

# ── Android app classes ──────────────────────────────────────────────────────
-keep class ai.kompile.chat.local.android.** { *; }

# ── SLF4J ────────────────────────────────────────────────────────────────────
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ── Suppress common warnings from third-party libs ───────────────────────────
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.swing.**
-dontwarn sun.misc.**
-dontwarn com.sun.jna.**
