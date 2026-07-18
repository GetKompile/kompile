package ai.kompile.project.server.git;

import java.util.regex.Pattern;

/**
 * Validation for hosted repository names.
 *
 * <p>Namespaces and slugs use a deliberately portable grammar: 1-64 ASCII letters, digits,
 * dots, underscores, or hyphens; the first character must be alphanumeric. Dot path segments
 * and path separators are therefore impossible. Git refs accepted by HTTP endpoints use the
 * same portable characters and are limited to 128 characters. This server intentionally accepts
 * simple branch/tag names and object IDs, not slash-containing ref paths.</p>
 */
public final class HostedProjectNames {

    public static final int MAX_IDENTIFIER_LENGTH = 64;
    public static final int MAX_REF_LENGTH = 128;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_IDENTIFIER_LENGTH - 1) + "}");
    private static final Pattern REF =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0," + (MAX_REF_LENGTH - 1) + "}");

    private HostedProjectNames() {
    }

    public static boolean isValidIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches()
                && !".".equals(value) && !"..".equals(value) && !value.contains("..");
    }

    public static boolean isValidRef(String value) {
        return value != null && REF.matcher(value).matches()
                && !".".equals(value) && !"..".equals(value) && !value.contains("..")
                && !value.endsWith(".lock");
    }

    public static void requireIdentifier(String field, String value) {
        if (!isValidIdentifier(value)) {
            throw new IllegalArgumentException(field + " must match " + IDENTIFIER.pattern()
                    + ", must not contain '..', and must be at most " + MAX_IDENTIFIER_LENGTH + " characters");
        }
    }

    public static void requireRef(String field, String value) {
        if (!isValidRef(value)) {
            throw new IllegalArgumentException(field + " is not a portable simple Git ref");
        }
    }
}
