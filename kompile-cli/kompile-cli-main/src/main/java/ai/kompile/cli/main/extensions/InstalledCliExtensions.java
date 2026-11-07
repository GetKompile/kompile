package ai.kompile.cli.main.extensions;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.extensions.process.ProcessCliExtension;
import ai.kompile.cli.main.extensions.process.ProcessExtensionStore;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Only explicitly installed user packages are considered; the working directory is never scanned. */
public final class InstalledCliExtensions {
    private InstalledCliExtensions() { }

    public static ProcessExtensionStore store() {
        return new ProcessExtensionStore(Info.homeDirectory().toPath().resolve("extensions"), new ObjectMapper());
    }

    public static CliExtensionCatalog discover() {
        return discover(store(), CliExtensionDiscovery.providers());
    }

    public static CliExtensionCatalog discover(ProcessExtensionStore store,
                                                Collection<? extends CliExtension> compiled) {
        List<CliExtension> selected = new ArrayList<>(compiled);
        try {
            for (var installed : store.installed()) selected.add(new ProcessCliExtension(installed));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read installed process extensions from " + store.root(), failure);
        }
        return new CliExtensionCatalog(selected, Set.of());
    }
}
