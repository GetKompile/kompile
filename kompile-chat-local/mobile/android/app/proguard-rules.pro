# ── Kompile chat core ────────────────────────────────────────────────────────
# Keep all public API of the chat core so reflection from the reasoning lib works.
-keep class ai.kompile.chat.local.** { *; }
-keep class ai.kompile.graph.reasoning.** { *; }

# ── JNA ──────────────────────────────────────────────────────────────────────
# JNA discovers method names via reflection; keep all JNA Library subinterfaces.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Library { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

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
