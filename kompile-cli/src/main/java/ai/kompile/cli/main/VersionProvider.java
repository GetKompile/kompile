package ai.kompile.cli.main;

import picocli.CommandLine;

import java.io.InputStream;
import java.util.Properties;

/**
 * Provides version information for the CLI, typically read from a properties file.
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    private static final String VERSION_PROPERTIES_PATH = "/META-INF/maven/ai.kompile/kompile-cli/pom.properties";
    // Fallback if the primary path is not found, or use a custom properties file.
    private static final String FALLBACK_VERSION_PROPERTIES_PATH = "version.properties";


    @Override
    public String[] getVersion() throws Exception {
        Properties props = new Properties();
        InputStream is = getClass().getResourceAsStream(VERSION_PROPERTIES_PATH);

        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(FALLBACK_VERSION_PROPERTIES_PATH);
        }

        if (is == null) {
            // If still null, provide a default or indicate unknown version
            return new String[]{"Kompile CLI: Unknown version (version.properties not found)"};
        }

        try {
            props.load(is);
            String version = props.getProperty("version", "Unknown version");
            String artifactId = props.getProperty("artifactId", "kompile-cli");
            // You can add more details like build timestamp if available in properties
            // String buildTimestamp = props.getProperty("buildTimestamp", "");
            return new String[]{
                    String.format("%s version %s", artifactId, version)
                    // Add more lines if needed, e.g., build date, Git commit
            };
        } catch (Exception e) {
            return new String[]{"Kompile CLI: Error reading version: " + e.getMessage()};
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }
}