package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop.ProviderChatFailure;
import ai.kompile.cli.main.chat.config.ChatConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** One bounded turn's fallback budget. Reused by the lead and native child loops. */
public final class ModelFailoverController {
    private final SessionModelLadder ladder;
    private final Supplier<ChatConfig> active;
    private final Function<ChatConfig, Boolean> activate;
    private final Consumer<String> notice;
    private final Set<String> tried = new LinkedHashSet<>();
    private int attempts;

    public ModelFailoverController(SessionModelLadder ladder, Supplier<ChatConfig> active,
                                   Function<ChatConfig, Boolean> activate, Consumer<String> notice) {
        this.ladder = ladder;
        this.active = active;
        this.activate = activate;
        this.notice = notice;
    }

    public boolean tryFailover(ProviderChatFailure failure) {
        return tryFailover(failure.failureKind(), failure.getMessage(), failure.replaySafe());
    }

    public boolean tryFailover(ai.kompile.cli.main.chat.config.DirectLlmClient.FailureKind kind,
                               String detail, boolean replaySafe) {
        if (ladder == null || !ladder.enabled() || ladder.isEmpty() || !replaySafe
                || !ladder.isSwitchEligible(kind)) return false;
        ChatConfig source = active.get();
        if (source == null) return false;
        String provider = source.getProvider();
        String model = source.getModel();
        String credential = source.getCredentialName();
        var sourceRung = new SessionModelLadder.ModelRung(provider, model, credential, null, null,
                source.getAuthenticationMethod());
        tried.add(sourceRung.key(provider, credential));
        ladder.markFailed(sourceRung, kind, detail, provider, credential);
        ladder.markFailed(new SessionModelLadder.ModelRung(provider, model, credential, null),
                kind, detail, provider, credential);
        while (true) {
            var next = ladder.next(kind, detail, provider, model,
                    credential, tried, attempts, source.getAuthenticationMethod());
            if (next.isEmpty()) { save(); return false; }
            var rung = next.get().rung();
            tried.add(rung.key(provider, credential));
            attempts++;
            try {
                ChatConfig candidate = candidate(source, rung);
                if (!Boolean.TRUE.equals(activate.apply(candidate))) continue;
            } catch (RuntimeException unusable) {
                // Never render credential resolution errors, tokens or provider bodies.
                notice.accept("[failover] Skipping unavailable fallback " + rung.describe(provider));
                continue;
            }
            ladder.recordSwitch(model, rung, kind, next.get().cooldown(), provider, credential);
            save();
            notice.accept("[failover] " + provider + "/" + model + " → " + rung.describe(provider)
                    + " · continuing from saved conversation");
            return true;
        }
    }

    static ChatConfig candidate(ChatConfig source, SessionModelLadder.ModelRung rung) {
        String provider = rung.provider() == null ? source.getProvider() : rung.provider();
        ChatConfig candidate = ChatRepl.buildModelProviderCandidateFrom(source, provider, rung.model(), null);
        if (rung.authenticationMethod() != null) candidate.setAuthenticationMethod(rung.authenticationMethod());
        if (rung.credentialName() != null || rung.authenticationMethod() != null) {
            // A wizard route is explicit, including native login with no stored
            // account. Do not inherit the exhausted account's token/inline key.
            candidate.setAuthenticationScope("session");
            candidate.setCredentialName(rung.credentialName());
        }
        candidate.setUltracode(false);
        candidate.setThinking(rung.thinking());
        if (!candidate.isValid()) throw new IllegalArgumentException("Invalid fallback route");
        if (candidate.isClaudeCliNative()) candidate.resolveClaudeCodeToken();
        else candidate.resolveRequestAuth();
        return candidate;
    }

    private void save() {
        try { ladder.save(); }
        catch (java.io.IOException ignored) { notice.accept("[failover] Could not persist runtime state"); }
    }
}
