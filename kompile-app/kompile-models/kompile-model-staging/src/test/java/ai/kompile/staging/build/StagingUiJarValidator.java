package ai.kompile.staging.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verify the exact executable JAR produced by Maven contains one coherent,
 * current model-staging UI bundle. This is intentionally a build-time main
 * class so the verify phase checks the packaged artifact, not target/classes.
 */
public final class StagingUiJarValidator {

    private static final String UI_PREFIX = "BOOT-INF/classes/static/model-staging/";
    private static final String INDEX_ENTRY = UI_PREFIX + "index.html";
    private static final Pattern LOCAL_ASSET = Pattern.compile(
            "(?:src|href)\\s*=\\s*[\"']([^\"']+\\.(?:js|css)(?:[?#][^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIN_BUNDLE = Pattern.compile("main-[A-Za-z0-9]+\\.js");
    private static final Pattern POLYFILLS_BUNDLE = Pattern.compile("polyfills-[A-Za-z0-9]+\\.js");
    private static final Pattern STYLES_BUNDLE = Pattern.compile("styles-[A-Za-z0-9]+\\.css");

    private static final List<String> REQUIRED_UI_LITERALS = List.of(
            "Build Mobile Chat Artifact",
            "Accelerator chat model (.sdz)",
            "Full offline graph-chat project (.kproject)",
            "kompile.staging.project-dir",
            "Discover GGUF and tokenizer files",
            "Select one GGUF/GGML model",
            "tokenizer.json",
            "tokenizer_config.json",
            "Import diagnostics",
            "Sanitized server history",
            "Next step:",
            "Staging pairing token",
            "Pair this session");

    private StagingUiJarValidator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: StagingUiJarValidator <exact-executable-jar> <legacy-source-ui-directory>");
        }

        Path executableJar = Path.of(args[0]).toAbsolutePath().normalize();
        Path legacySourceDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executableJar)) {
            throw new IllegalStateException("Executable JAR was not produced: " + executableJar);
        }
        if (Files.exists(legacySourceDirectory)) {
            throw new IllegalStateException(
                    "Generated UI leaked into the source tree: " + legacySourceDirectory);
        }

        try (JarFile jar = new JarFile(executableJar.toFile())) {
            String index = readRequiredEntry(jar, INDEX_ENTRY);
            Set<String> localAssets = referencedLocalAssets(index);
            if (localAssets.isEmpty()) {
                throw new IllegalStateException("Packaged index.html references no local JavaScript or CSS assets");
            }
            for (String asset : localAssets) {
                requireEntry(jar, UI_PREFIX + asset);
            }

            Set<String> packagedMain = topLevelMatchingEntries(jar, MAIN_BUNDLE);
            Set<String> packagedPolyfills = topLevelMatchingEntries(jar, POLYFILLS_BUNDLE);
            Set<String> packagedStyles = topLevelMatchingEntries(jar, STYLES_BUNDLE);
            String main = requireExactlyOneReferenced("main", packagedMain, localAssets);
            requireExactlyOneReferenced("polyfills", packagedPolyfills, localAssets);
            requireExactlyOneReferenced("styles", packagedStyles, localAssets);

            String mainJavaScript = readRequiredEntry(jar, UI_PREFIX + main);
            List<String> missingLiterals = new ArrayList<>();
            for (String literal : REQUIRED_UI_LITERALS) {
                if (!mainJavaScript.contains(literal)) {
                    missingLiterals.add(literal);
                }
            }
            if (!missingLiterals.isEmpty()) {
                throw new IllegalStateException(
                        "Executable JAR contains a stale model-staging UI; missing literals: " + missingLiterals);
            }

            System.out.println("Validated executable JAR UI bundle: " + executableJar);
            System.out.println("  main=" + main);
            System.out.println("  polyfills=" + packagedPolyfills.iterator().next());
            System.out.println("  styles=" + packagedStyles.iterator().next());
        }
    }

    private static Set<String> referencedLocalAssets(String index) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = LOCAL_ASSET.matcher(index);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) {
                continue;
            }
            String normalized = stripQueryAndFragment(raw);
            while (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            if (normalized.startsWith("model-staging/")) {
                normalized = normalized.substring("model-staging/".length());
            }
            if (normalized.isBlank()
                    || normalized.equals("..")
                    || normalized.startsWith("../")
                    || normalized.contains("/../")) {
                throw new IllegalStateException("Unsafe asset reference in packaged index.html: " + raw);
            }
            result.add(normalized);
        }
        return result;
    }

    private static String stripQueryAndFragment(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return value.substring(0, end);
    }

    private static Set<String> topLevelMatchingEntries(JarFile jar, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(UI_PREFIX))
                .map(name -> name.substring(UI_PREFIX.length()))
                .filter(name -> !name.contains("/"))
                .filter(name -> pattern.matcher(name).matches())
                .forEach(names::add);
        return names;
    }

    private static String requireExactlyOneReferenced(
            String family, Set<String> packaged, Set<String> referenced) {
        if (packaged.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one packaged " + family + " hash, found " + packaged);
        }
        String asset = packaged.iterator().next();
        if (!referenced.contains(asset)) {
            throw new IllegalStateException(
                    "Packaged index.html does not reference the only " + family + " asset: " + asset);
        }
        return asset;
    }

    private static void requireEntry(JarFile jar, String name) {
        if (jar.getJarEntry(name) == null) {
            throw new IllegalStateException("Packaged index.html references missing JAR entry: " + name);
        }
    }

    private static String readRequiredEntry(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) {
            throw new IllegalStateException("Executable JAR is missing required entry: " + name);
        }
        try (InputStream input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
