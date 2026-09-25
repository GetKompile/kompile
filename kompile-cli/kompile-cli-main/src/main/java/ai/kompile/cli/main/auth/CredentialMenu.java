package ai.kompile.cli.main.auth;

import ai.kompile.cli.common.auth.ManagedCredential;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

/** Shared paged credential selection for setup and running or resumed chats. */
public final class CredentialMenu {
    private CredentialMenu() {
    }

    /** Hide expired access tokens from selection without deleting renewable accounts. */
    public static List<CredentialStore.CredentialInfo> unexpired(
            List<CredentialStore.CredentialInfo> credentials) {
        long now = System.currentTimeMillis();
        return credentials.stream()
                .filter(info -> !ManagedCredential.OAUTH.equals(info.type())
                        || info.expiresAt() == Long.MAX_VALUE || info.expiresAt() > now)
                .toList();
    }

    /**
     * Pages all choices to fit the terminal without limiting the available accounts. Returns
     * the index in the complete list, or -1 on cancellation. The caller retains
     * ownership of its reader and terminal.
     */
    public static int select(LineReader reader, String title, List<String> labels) {
        return select(reader, title, labels, null);
    }

    /** Replace the current chat modal on each page without taking over its terminal. */
    public static int select(LineReader reader, String title, List<String> labels,
                             BiConsumer<String, List<String>> pageRenderer) {
        return select(reader, title, labels, -1, pageRenderer);
    }

    public static int select(LineReader reader, String title, List<String> labels, int defaultIndex,
                             BiConsumer<String, List<String>> pageRenderer) {
        return new AuthWizard.TerminalPrompter(reader.getTerminal(), reader, pageRenderer)
                .select(title, labels, defaultIndex);
    }

    public static int defaultIndex(CredentialStore store, String provider,
                                   List<CredentialStore.CredentialInfo> credentials) throws IOException {
        String name = store.defaultCredentialName(provider);
        for (int i = 0; i < credentials.size(); i++) {
            if (credentials.get(i).credentialName().equals(name)) return i;
        }
        // A remembered account on a different authentication route is not a default here.
        for (int i = 0; i < credentials.size(); i++) {
            if (credentials.get(i).active()) return i;
        }
        return credentials.isEmpty() ? -1 : 0;
    }
}
