package ai.kompile.graphchangetracking.service;

import org.springframework.stereotype.Component;

@Component
public class MutationContextHolder {

    private static final ThreadLocal<MutationContext> CONTEXT = new ThreadLocal<>();

    public record MutationContext(String changesetId, String triggerSource, String actorId) {}

    public void set(String changesetId, String triggerSource, String actorId) {
        CONTEXT.set(new MutationContext(changesetId, triggerSource, actorId));
    }

    public MutationContext current() {
        MutationContext ctx = CONTEXT.get();
        if (ctx == null) {
            return new MutationContext(null, "API", null);
        }
        return ctx;
    }

    public void clear() {
        CONTEXT.remove();
    }
}
