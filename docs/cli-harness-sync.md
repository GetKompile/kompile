# Sync external harness settings and logins

`kompile sync` can now manage **Codex and Claude Code** settings and file-based login credentials between two Kompile installs. Both installs must use sync protocol v2 (update both before syncing).

The default remains `skills,memories,roles`. External harness files are opt-in:

```sh
kompile sync peer add workstation --ssh user@workstation
# Preview (hashes and paths only; no payload transfer)
kompile sync workstation --only harness-settings,harness-credentials --dry-run
# Explicitly authorize transferring potentially secret files
kompile sync workstation --only harness-settings,harness-credentials --yes
```

`--direction push|pull|both`, normal conflict detection, `--force-local` / `--force-remote`, and opt-in `--allow-delete` apply to these families too. Divergent first-sync files cause an abort unless a winner is explicitly selected. Settings are copied whole, **not merged**. Preview before forcing a winner.

## Files mounted

| Family | Provider | Default files |
|---|---|---|
| `harness-settings` | Codex | `~/.codex/config.toml` (providers, models, profiles, MCP settings) |
| `harness-settings` | Claude | `~/.claude/settings.json`, `~/.claude/settings.local.json`, `~/.claude.json` (global preferences, sign-in/MCP state and project trust decisions) |
| `harness-credentials` | Codex | `~/.codex/auth.json` |
| `harness-credentials` | Claude | `~/.claude/.credentials.json` |

Only these exact files are exposed: no recursive home-folder copies, transcripts, databases, backups, caches, model weights, or logs. Kompile's own `provider-skills` family is unchanged. Harness families require global scope; credentials are never copied into project directories.

The wire paths carry provider identity (for example `codex/auth.json`). Claude's global state uses the logical path `claude/global-state.json`, mapped to `.claude.json` on each side. Absolute project paths, trust decisions, hook commands, and provider-specific paths inside settings are **not rewritten** for the receiving machine. Review these before applying.

## Roots and local profiles

Each endpoint normally resolves its own user home and honors `CODEX_HOME` (or `kompile.codex.home`) and `CLAUDE_CONFIG_DIR`. With a custom Claude config directory, global state is its `.claude.json` file.

Use `--user-home` and `--remote-user-home` to explicitly select different user profiles. An explicit user home uses that profile's `.codex`, `.claude`, and `.claude.json`, ignoring provider-home environment overrides. In local mode the remote profile is required when syncing harness files, to avoid accidentally using the invoking user's credentials for both installs:

```sh
kompile sync workstation --local --home /other/profile/.kompile \
  --remote-user-home /other/profile \
  --only harness-settings,harness-credentials --yes
```

`--home` selects the other **Kompile** home, not the external harness home. Existing SSH peer `--home` registrations are now passed through to the endpoint. `--remote-user-home` may also select an explicit profile over SSH. Paths passed over SSH are shell-quoted; a leading `~/` is expanded by the endpoint to its own user home.

## Security and user notification

- The CLI, wizard, and endpoint display a warning when a harness family is selected. Noninteractive transfer requires `--yes`; preview needs no authorization to write. `--only all` now includes harness families and therefore triggers the same warning/authorization.
- **Settings may contain API keys too**, so treat both families as sensitive. Transfer only between trusted accounts/devices over SSH (or the local subprocess). Base64 protocol payloads are not encryption; SSH supplies transport encryption.
- Close Codex/Claude on both machines before syncing. Sync checks targets against their inventory hashes and refuses detected concurrent changes. These checks are not a transactional filesystem lock; another harness can still race the transfer.
- Harness I/O uses anchored directory handles with no-follow opens, and private random temporary files for replacement: POSIX files `0600`, newly created directories `0700`. Harness transfer requires a filesystem/JVM provider supporting secure directory streams and POSIX permissions; unsupported providers fail closed rather than silently using unsafe I/O. Existing parent-directory permissions are not changed. Symlink files and ancestors are refused.
- Baselines are bound to both endpoints' profile mounts. Changing user homes or provider roots under an existing peer name aborts before transfer; register a separate peer name for another profile. Forced deletion conflicts also require `--allow-delete`, otherwise the entire plan aborts.
- Plans, baselines, and audit output contain paths/hashes/actions only, never credential payloads.
- Missing file-based logins are reported per provider on each side, rather than silently implying a keychain login was copied.
- **OS keychains and environment-only credentials are not exported.** Claude's macOS keychain and Codex's keyring require a separate login when no file credential exists. Sync does not force a provider to switch credential storage.
- Copied OAuth refresh tokens may expire or cause another install's session to become invalid. If a harness rejects a copied login, sign in again there. Nothing is silently exported from the keychain.

Provider contracts: [Codex authentication](https://developers.openai.com/codex/auth/), [Claude authentication](https://code.claude.com/docs/en/authentication), [Claude settings](https://code.claude.com/docs/en/settings), and [Claude custom config layout](https://github.com/anthropics/claude-code/issues/3833).
