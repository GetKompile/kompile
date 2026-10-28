package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialMenu;
import ai.kompile.cli.main.chat.SessionModelLadder.ModelRung;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Shared session/subagent failover editor. Only the caller commits an accepted draft. */
public final class FailoverConfigWizard {
    private FailoverConfigWizard() { }

    public record Draft(boolean enabled, List<ModelRung> rungs) {
        public Draft { rungs = List.copyOf(rungs); }
    }

    @FunctionalInterface
    interface RoutePicker {
        ModelRung pick(LineReader reader, ChatConfig current, BiConsumer<String, List<String>> page) throws IOException;
    }

    /** Returns null on cancel, EOF or interruption; the input list and config are never modified. */
    public static Draft edit(LineReader reader, boolean enabled, List<ModelRung> rungs, ChatConfig current,
                             BiConsumer<String, List<String>> page) {
        return edit(reader, enabled, rungs, current, page, FailoverConfigWizard::pickRoute);
    }

    static Draft edit(LineReader reader, boolean enabled, List<ModelRung> rungs, ChatConfig current,
                      BiConsumer<String, List<String>> page, RoutePicker picker) {
        var draft = new ArrayList<>(rungs);
        String notice = "Changes are staged until Save. Only credential references are stored.";
        try {
            while (true) {
                var lines = new ArrayList<>(describe(enabled, draft));
                lines.addAll(List.of("", "1. Add fallback route", "2. Remove fallback route", "3. Reorder fallback routes",
                        "4. " + (enabled ? "Disable" : "Enable") + " failover", "5. Save and close", "0. Cancel", notice));
                page.accept("Model failover", List.copyOf(lines));
                String choice = read(reader, "Choice: ");
                try {
                    switch (choice) {
                        case "0", "cancel", "back" -> { return null; }
                        case "1" -> {
                            ModelRung rung = picker.pick(reader, current == null ? new ChatConfig() : current.copy(), page);
                            if (rung == null) { notice = "Route selection cancelled. No route added."; continue; }
                            if (!rung.hasModel()) throw new IllegalArgumentException("A fallback route requires a model.");
                            if (draft.stream().anyMatch(existing -> sameRoute(existing, rung))) {
                                throw new IllegalArgumentException("That fallback route is already configured. Remove it first to change thinking.");
                            }
                            draft.add(rung);
                        }
                        case "2" -> draft.remove(index(read(reader, "Remove route number: "), draft.size()));
                        case "3" -> {
                            int from = index(read(reader, "Move route number: "), draft.size());
                            int to = index(read(reader, "To position: "), draft.size());
                            draft.add(to, draft.remove(from));
                        }
                        case "4" -> enabled = !enabled;
                        case "5", "save" -> { return new Draft(enabled, draft); }
                        default -> throw new IllegalArgumentException("Choose 0 through 5.");
                    }
                    notice = "Unsaved changes; choose 5 to save or 0 to cancel.";
                } catch (NumberFormatException e) {
                    notice = "Route indices must be numbers.";
                } catch (IllegalArgumentException e) {
                    notice = e.getMessage();
                } catch (IOException e) {
                    notice = "Could not prepare the saved route. Check /auth and retry.";
                }
            }
        } catch (UserInterruptException | EndOfFileException ignored) {
            return null;
        }
    }

    public static List<String> describe(boolean enabled, List<ModelRung> rungs) {
        var lines = new ArrayList<String>();
        lines.add("Model failover: " + (enabled ? "on" : "off"));
        if (rungs.isEmpty()) lines.add("Fallback routes: none");
        for (int i = 0; i < rungs.size(); i++) lines.add((i + 1) + ". " + describe(rungs.get(i)));
        return List.copyOf(lines);
    }

    public static String describe(ModelRung rung) {
        return (rung.provider() == null ? "current provider" : rung.provider()) + "/" + rung.model()
                + " · credential: " + (rung.credentialName() == null ? "route default / existing login" : rung.credentialName())
                + " · auth: " + (rung.authenticationMethod() == null ? "route default" : rung.authenticationMethod())
                + " · thinking: " + (rung.thinking() == null ? "model default" : rung.thinking())
                + (rung.label() == null ? "" : " · " + rung.label());
    }

    private static boolean sameRoute(ModelRung left, ModelRung right) {
        return (left.provider() == null ? right.provider() == null
                : left.provider().equalsIgnoreCase(right.provider()))
                && java.util.Objects.equals(left.model(), right.model())
                && java.util.Objects.equals(left.credentialName(), right.credentialName())
                && java.util.Objects.equals(left.authenticationMethod(), right.authenticationMethod());
    }

    private static ModelRung pickRoute(LineReader reader, ChatConfig current,
                                       BiConsumer<String, List<String>> page) throws IOException {
        List<String> vendors = SetupWizard.providerPickerOrder();
        int vendorChoice = CredentialMenu.select(reader, "Fallback provider", vendors,
                vendors.indexOf(SetupWizard.vendorForProvider(current.getProvider())), page);
        if (vendorChoice < 0) return null;
        String vendor = vendors.get(vendorChoice);
        List<SetupWizard.AuthMethod> methods = SetupWizard.authMethodsForPicker(vendor);
        if (methods.isEmpty()) throw new IllegalArgumentException("No authentication route for " + vendor);
        int authChoice = methods.size() == 1 ? 0 : CredentialMenu.select(reader, "Fallback authentication route",
                methods.stream().map(method -> SetupWizard.authMethodLabel(vendor, method)).toList(), page);
        if (authChoice < 0) return null;
        SetupWizard.AuthMethod method = methods.get(authChoice);
        if (method == SetupWizard.AuthMethod.API_KEY_CREDITS) {
            throw new IllegalArgumentException("Endpoint-specific credits routes cannot be saved as a fallback. Choose the standard API-key route instead.");
        }
        var authentication = SetupWizard.selectSavedAuthentication(reader, vendor, method, page, null);
        if (authentication == null) throw new IllegalArgumentException("No saved authentication selected. Use /auth to add a credential if needed.");
        ChatConfig route = current.copy();
        route.setProvider(authentication.provider());
        route.setApiKey(null);
        route.setAuthenticationMethod(authentication.authMethod().configValue());
        route.setAuthenticationScope("session");
        route.setCredentialName(authentication.credentialName());
        route.setBaseUrl("custom".equals(authentication.provider())
                ? ("custom".equals(current.getProvider()) ? current.getBaseUrl() : null)
                : SetupWizard.baseUrlForAuth(authentication.provider(), method));
        if ("custom".equals(authentication.provider()) && (route.getBaseUrl() == null || route.getBaseUrl().isBlank())) {
            throw new IllegalArgumentException("Configure the custom provider endpoint with /auth before adding a fallback.");
        }
        route.setModel(null);
        route.setThinking(null);
        route.setFastMode(false);
        route.setUltracode(false);
        var model = SetupWizard.pickModel(reader, route, page);
        return model == null ? null : new ModelRung(authentication.provider(), model.model(),
                authentication.credentialName(), null, model.thinking(), authentication.authMethod().configValue());
    }

    private static int index(String input, int size) {
        int index = Integer.parseInt(input) - 1;
        if (index < 0 || index >= size) throw new IllegalArgumentException("Route number is out of range.");
        return index;
    }

    private static String read(LineReader reader, String prompt) {
        String answer = reader.readLine(prompt);
        if (answer == null) throw new EndOfFileException();
        answer = answer.strip().toLowerCase(java.util.Locale.ROOT);
        if (answer.equals("cancel") || answer.equals("back")) throw new UserInterruptException("");
        return answer;
    }
}
