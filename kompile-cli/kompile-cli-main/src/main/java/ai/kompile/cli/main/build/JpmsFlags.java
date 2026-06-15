package ai.kompile.cli.main.build;

import java.util.Collections;
import java.util.List;

/**
 * Canonical set of JPMS module-open flags required by ND4J, JavaCPP, and
 * related native dependencies. Every generator that emits JVM launch flags
 * MUST reference these lists instead of maintaining its own copy.
 */
public final class JpmsFlags {

    private JpmsFlags() {}

    /** Module opens required by ND4J / JavaCPP / bytedeco for deep reflection. */
    public static final List<String> ADD_OPENS = List.of(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
            "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
            "--add-opens=java.base/sun.misc=ALL-UNNAMED",
            "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
    );

    /** Module exports for JDK-internal APIs needed by native bindings. */
    public static final List<String> ADD_EXPORTS = List.of(
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    );

    /** Combined opens + exports as a single space-delimited string for Maven properties. */
    public static String asMavenArgLine() {
        StringBuilder sb = new StringBuilder();
        for (String flag : ADD_OPENS) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(flag);
        }
        for (String flag : ADD_EXPORTS) {
            sb.append(' ').append(flag);
        }
        return sb.toString();
    }

    /** Formats flags for a Unix shell script with backslash line continuations. */
    public static String asShellFlags(String indent) {
        StringBuilder sb = new StringBuilder();
        List<String> all = new java.util.ArrayList<>(ADD_OPENS);
        all.addAll(ADD_EXPORTS);
        for (int i = 0; i < all.size(); i++) {
            sb.append(indent).append(all.get(i));
            if (i < all.size() - 1) sb.append(" \\");
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Formats flags for a Windows batch script with caret line continuations. */
    public static String asBatchFlags(String indent) {
        StringBuilder sb = new StringBuilder();
        List<String> all = new java.util.ArrayList<>(ADD_OPENS);
        all.addAll(ADD_EXPORTS);
        for (int i = 0; i < all.size(); i++) {
            sb.append(indent).append(all.get(i));
            if (i < all.size() - 1) sb.append(" ^");
            sb.append('\n');
        }
        return sb.toString();
    }
}
