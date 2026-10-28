/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code subscription credential captured through {@code claude setup-token}.
 *
 * <p><b>Why this flow exists.</b> The Claude Code route historically ran on the
 * user's ambient {@code claude /login} state: Kompile stored no credential and
 * silently depended on whatever login the machine happened to have. This flow
 * replaces that dependency with a Kompile-managed credential: the user approves
 * the same browser authorization as {@code /login}, and the printed one-year
 * setup token is captured into {@link ManagedCredential} storage. Kompile then
 * injects {@code CLAUDE_CODE_OAUTH_TOKEN} wherever it spawns the {@code claude}
 * binary, so the route no longer depends on ambient login state.</p>
 *
 * <p><b>Token facts (verified against Claude Code docs + CLI 2.1.282).</b> The
 * token is a one-year OAuth token for Pro/Max/Team/Enterprise subscriptions,
 * printed once by {@code claude setup-token} and saved <em>nowhere</em> by the
 * CLI itself. It has no refresh grant, carries only model-request scope (no
 * Remote Control, no claude.ai connectors), and is NOT an {@code ANTHROPIC_API_KEY}
 * — Claude Code gives that variable precedence over it, which is why the chat
 * route must keep stripping it ({@code ClaudeCliClient}). Claude Code has no
 * revocation endpoint, so {@link #revoke} stays a local delete and the UI must
 * point at claude.ai → Settings → Claude Code.</p>
 *
 * <p><b>Capture.</b> {@code claude setup-token} is an Ink screen that draws the
 * token as wrapped text: on a pipe (no width, so 80 columns) the ~108-character
 * token spans two lines, each wrapped in color codes. Capture strips escape
 * sequences and rejoins the token-only lines that follow a token running to the
 * end of its line; a one-line match would store a truncated token. The same
 * applies to a pasted token, which carries the terminal's line breaks.</p>
 *
 * <p><b>Redaction.</b> The child keeps the user's terminal for input (its
 * paste-code prompt, Esc) while this flow reads its output. Each distinct
 * status line is shown once, without escape sequences, and the token, including
 * its wrapped continuation lines, is replaced by a placeholder. The captured
 * token is returned only inside the {@link ManagedCredential}.</p>
 */
public final class ClaudeCodeOAuthFlow implements OAuthProviderFlow {
    /** Credential-store wire provider id, referenced by every spawn seam. */
    public static final String PROVIDER_ID = "claude-code";
    /**
     * Vendor shown in menus: the existing single "anthropic" entry. Kompile
     * controls the Claude subscription login like every other vendor's, through
     * the same wizard path — there is exactly one Anthropic vendor, and this
     * flow is its OAuth method (setup-token capture), not a separate surface.
     */
    static final String USER_FACING_PROVIDER_ID = "anthropic";
    /** The one env var that carries a Claude Code setup token into any spawn. */
    public static final String OAUTH_TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN";
    static final String SETUP_TOKEN_METHOD = "setup-token";
    static final String MANUAL_METHOD = "manual";
    static final long ONE_YEAR_SECONDS = 365L * 24L * 3600L;

    /** Shape of a Claude Code setup token ({@code sk-ant-oat01-…}). */
    static final Pattern TOKEN_PATTERN =
            Pattern.compile("sk-ant-oat[0-9]{0,2}-[A-Za-z0-9_-]{8,}");
    /** A token's first line: the prefix and whatever part of the body fits. */
    private static final Pattern TOKEN_START = Pattern.compile("sk-ant-oat[0-9]{0,2}-[A-Za-z0-9_-]*");
    /** A line holding only token characters: a continuation of a wrapped token. */
    private static final Pattern TOKEN_BODY = Pattern.compile("[A-Za-z0-9_-]+");
    /** CSI (colors, cursor moves, erases), OSC (hyperlinks, titles) and other escapes. */
    private static final Pattern ESCAPE_SEQUENCE = Pattern.compile(
            "\033\\[[0-?]*[ -/]*[@-~]|\033\\][^\007\033]*(?:\007|\033\\\\)|\033[ -/]*[0-~]");
    static final String REDACTED = "sk-ant-oat<redacted>";
    /** Variables that make Claude Code warn that authentication is already configured. */
    private static final List<String> CONFLICTING_ENVIRONMENT = List.of("ANTHROPIC_API_KEY", OAUTH_TOKEN_ENV);

    private static final String SETUP_TOKEN_GUIDANCE =
            "Claude Code setup tokens have no refresh grant. Run the login wizard again "
                    + "to capture a fresh token. "
                    + "Revoke a leaked one at claude.ai → Settings → Claude Code.";

    private final Supplier<List<String>> setupTokenCommand;

    public ClaudeCodeOAuthFlow() {
        this(() -> List.of("claude", "setup-token"));
    }

    /** Test seam: the command this flow spawns for the setup-token method. */
    ClaudeCodeOAuthFlow(Supplier<List<String>> setupTokenCommand) {
        this.setupTokenCommand = setupTokenCommand;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String userFacingProviderId() {
        return USER_FACING_PROVIDER_ID;
    }

    @Override
    public boolean supportsApiKey() {
        // A setup token authenticates Claude Code routes only; it is never an
        // ANTHROPIC_API_KEY, and this vendor's API-key route is a different
        // credential stored under the "anthropic" provider.
        return false;
    }

    @Override
    public String displayName() {
        return "Claude (Claude Code subscription — setup token)";
    }

    @Override
    public List<LoginMethod> loginMethods() {
        return List.of(
                new LoginMethod(SETUP_TOKEN_METHOD, "claude setup-token (browser)"),
                new LoginMethod(MANUAL_METHOD, "Paste an existing setup token"));
    }

    @Override
    public String defaultLoginMethod() {
        return SETUP_TOKEN_METHOD;
    }

    @Override
    public ManagedCredential login(LoginOptions options, Interaction interaction)
            throws IOException, InterruptedException {
        String method = options.methodOr(defaultLoginMethod());
        if (MANUAL_METHOD.equals(method)) {
            return manualLogin(interaction);
        }
        if (SETUP_TOKEN_METHOD.equals(method)) {
            return setupTokenLogin(interaction);
        }
        throw new IOException("Unsupported Claude login method: " + method);
    }

    private ManagedCredential manualLogin(Interaction interaction) throws IOException {
        interaction.info("Paste the token printed by `claude setup-token` "
                + "(claude.ai → Settings → Claude Code lists active tokens).");
        String pasted = interaction.secret("Setup token:");
        // A token copied from the terminal carries the line breaks Ink wrapped it
        // at, and token characters never include whitespace.
        String token = pasted == null ? null : extractToken(pasted.replaceAll("\\s+", ""));
        if (token == null) {
            throw new IOException("That does not look like a Claude Code setup token "
                    + "(expected sk-ant-oat…). Nothing was stored.");
        }
        return credential(token);
    }

    private ManagedCredential setupTokenLogin(Interaction interaction)
            throws IOException, InterruptedException {
        List<String> command = setupTokenCommand.get();
        interaction.info("Starting `claude setup-token`. Approve access in the browser "
                + "when it opens; the token is captured automatically.");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        // Errors (policy refusals, a failed handshake) come back through the same
        // sanitized relay instead of writing over a chat screen.
        builder.redirectErrorStream(true);
        CONFLICTING_ENVIRONMENT.forEach(builder.environment()::remove);
        String output = interaction.withTerminal(() -> runCapturing(builder, interaction));
        String token = extractToken(output);
        if (token == null) {
            throw new IOException("`claude setup-token` finished without printing a setup token. "
                    + "Nothing was stored. Use the paste method to enter a token manually.");
        }
        interaction.info("Setup token captured and stored. Claude Code prints it only once; "
                + "a copy lives in Kompile's credential store.");
        return credential(token);
    }

    /** Run the child to completion, relaying redacted status lines; returns its raw output. */
    private static String runCapturing(ProcessBuilder builder, Interaction interaction)
            throws IOException, InterruptedException {
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Could not run `claude setup-token` (is Claude Code "
                    + "installed and on PATH?). Alternative: run it in a terminal yourself "
                    + "and choose the paste method here.", e);
        }
        StringBuilder output = new StringBuilder();
        Redactor redactor = new Redactor();
        // Ink redraws whole frames; with escape sequences removed they repeat.
        Set<String> shown = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                String visible = redactor.apply(stripEscapes(line)).strip();
                if (!visible.isEmpty() && shown.add(visible)) {
                    interaction.info(visible);
                }
            }
        }
        int exit = process.waitFor();
        if (exit != 0 && extractToken(output.toString()) == null) {
            throw new IOException("`claude setup-token` exited with code " + exit
                    + " before printing a setup token. Nothing was stored.");
        }
        return output.toString();
    }

    private ManagedCredential credential(String token) {
        return ManagedCredential.oauth(
                token,
                "", // setup tokens have no refresh grant
                OAuthSupport.expiryFromNow(ONE_YEAR_SECONDS, 0L),
                Map.of(
                        "tokenKind", "setup-token",
                        "issuedBy", "claude setup-token",
                        "revocation", "claude.ai → Settings → Claude Code"));
    }

    @Override
    public ManagedCredential refresh(ManagedCredential credential) throws IOException {
        throw new IOException(SETUP_TOKEN_GUIDANCE);
    }

    @Override
    public RequestAuth toRequestAuth(ManagedCredential credential) {
        // The Claude Code route consumes this token as CLAUDE_CODE_OAUTH_TOKEN on
        // claude spawns, not as a Bearer header on a Kompile HTTP call. The header
        // form keeps the RequestAuth contract honest for tooling that does relay it.
        return RequestAuth.oauth(
                credential.getAccess(),
                null,
                Map.of("Authorization", "Bearer " + credential.getAccess()));
    }

    /**
     * First setup token in {@code text}, or {@code null}. A token that runs to the
     * end of its line continues on the token-only lines below it.
     */
    static String extractToken(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] lines = stripEscapes(text).split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = TOKEN_START.matcher(lines[i]);
            while (matcher.find()) {
                StringBuilder token = new StringBuilder(matcher.group());
                if (matcher.end() == lines[i].stripTrailing().length()) {
                    for (int next = i + 1; next < lines.length; next++) {
                        String continuation = lines[next].strip();
                        if (!TOKEN_BODY.matcher(continuation).matches()) {
                            break;
                        }
                        token.append(continuation);
                    }
                }
                if (TOKEN_PATTERN.matcher(token).matches()) {
                    return token.toString();
                }
            }
        }
        return null;
    }

    /** Every token-shaped match in {@code line} replaced with the redacted form. */
    static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(line);
        return matcher.replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    static String stripEscapes(String text) {
        return text == null ? null : ESCAPE_SEQUENCE.matcher(text).replaceAll("");
    }

    /**
     * Line-by-line redaction that also hides a wrapped token's continuation
     * lines. Feed it escape-free lines in output order.
     */
    static final class Redactor {
        private boolean continuing;

        String apply(String line) {
            if (continuing) {
                if (TOKEN_BODY.matcher(line.strip()).matches()) {
                    return "";
                }
                continuing = false;
            }
            Matcher matcher = TOKEN_START.matcher(line);
            StringBuilder redacted = new StringBuilder();
            int copied = 0;
            while (matcher.find()) {
                redacted.append(line, copied, matcher.start()).append(REDACTED);
                copied = matcher.end();
                continuing = copied == line.stripTrailing().length();
            }
            return copied == 0 ? line : redacted.append(line, copied, line.length()).toString();
        }
    }

    /**
     * Relay the globally active setup token to a {@code claude} process that
     * belongs to no chat session (the judge pool) as {@value #OAUTH_TOKEN_ENV}.
     * A chat's own claude process gets its selected credential instead
     * ({@code ChatConfig.resolveClaudeCodeToken()}). A variable the user set wins.
     * A missing, expired or unreadable credential changes nothing, so Claude
     * Code's own login applies.
     */
    public static void relayManagedToken(Map<String, String> environment, CredentialStore store) {
        if (environment.containsKey(OAUTH_TOKEN_ENV) || store == null) {
            return;
        }
        try {
            ManagedCredential credential = store.read(PROVIDER_ID);
            if (credential != null && credential.isOAuth()
                    && !credential.expiresWithin(60_000L, System.currentTimeMillis())) {
                environment.put(OAUTH_TOKEN_ENV, credential.getAccess());
            }
        } catch (IOException | RuntimeException ignored) {
            // A store hiccup must not break the spawn; Claude Code's own login remains.
        }
    }

    /** Whether {@code command} is the claude binary, bare or by path. */
    public static boolean isClaudeBinary(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            Path name = Path.of(command.strip()).getFileName();
            return name != null && (name.toString().equals("claude") || name.toString().equals("claude.exe"));
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }
}
