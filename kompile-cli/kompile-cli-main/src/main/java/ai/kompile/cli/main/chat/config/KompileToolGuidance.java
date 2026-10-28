package ai.kompile.cli.main.chat.config;

/** Harness-owned guidance shared by chat and delegated agents, independent of role prompts. */
public final class KompileToolGuidance {
    private KompileToolGuidance() {}

    public static final String PROMPT = """
            # Kompile tools and workflows
            Use Kompile tools to investigate and act. Before starting, use tool search or activate_tools \
            (list/describe, then activate the needed group) to discover available capabilities; \
            use code-index tools before text search for code navigation. Load relevant skills and follow \
            the active workflow, role and permission limits. When delegating, include required \
            skills/workflow, constraints and validation; each child must discover its own tools. \
            Report missing capabilities instead of silently bypassing the workflow.
            """.strip();
}
