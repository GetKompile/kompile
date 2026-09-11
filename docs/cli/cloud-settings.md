# Cloud-managed CLI settings

Kompile SaaS can store named, versioned profiles of Kompile CLI settings. Sync is explicit and offline-safe: normal CLI startup never contacts the cloud, and local files remain the runtime source of truth.

## Commands

Authenticate first:

```bash
kompile cloud login
```

Then manage profiles with:

```bash
kompile cloud settings list
kompile cloud settings get default
kompile cloud settings push default --scope user
kompile cloud settings push team-laptop --scope all --project-dir .
kompile cloud settings push default --scope user --dry-run --json
kompile cloud settings pull default --preview
kompile cloud settings pull default --mode append
kompile cloud settings pull default --mode override
kompile cloud settings delete default --yes
```

`settings` also has the alias `profiles`. Profile names contain lowercase letters, digits, `.`, `_`, or `-`, are at most 64 characters, and begin with a letter or digit. Descriptions are limited to 500 printable characters and cannot contain credential-bearing text. With `--dry-run --json`, stdout is only the bundle JSON; redaction warnings go to stderr.

## Scopes

- `user` (the push default): JSON settings under `~/.kompile/config/`, plus portable top-level user settings such as chat, harness, staging, code-graph, and LSP settings.
- `project`: JSON settings under `<project>/config/` and selected files under `<project>/.kompile/`.
- `all`: both user and project settings.

Project settings in a pulled profile are restored under `--project-dir`, or under the project resolved from the current directory. User settings always return to `~/.kompile`.

Runtime data is not a setting and is never included. This excludes credentials, SaaS login tokens, channel connection stores, project registration, checkpoints, conversations, indexes, performance data, and provider session state.

## Secret policy

Cloud profiles are for non-secret settings. During `push`, the CLI recursively removes common secret fields and credential-bearing values, including passwords, API/access/private/signing keys, canonical `auth` objects, tokens (including URL query tokens), cookies, Basic/Bearer authorization values, connection strings, webhook URLs, credential-bearing URLs (including standard and Oracle JDBC URLs), and PEM private keys. The command prints every removed JSON path before upload.

Environment references are portable and remain in a profile:

```json
{
  "apiKey": "${OPENAI_API_KEY}"
}
```

The SaaS API applies the same validation independently and rejects unsafe clients. This policy is defense in depth, not a general-purpose secret vault: do not disguise a secret under an unrelated field name. Use `kompile auth`, environment variables, or an external secret manager for credentials.

A pull never overwrites or deletes a local secret field. If cloud data changes the type of an object that contains a local secret, the pull fails instead of discarding the credential.

## Merge behavior and precedence

`pull --mode append` recursively merges cloud objects into existing files. Cloud values update matching non-secret keys while local-only keys remain.

`pull --mode override` replaces non-secret file content with the cloud version. Existing local secret leaves are restored afterward, including nested secrets whose parent is absent from the cloud profile.

Both modes take a shared local settings lock, validate every file before writing, verify that each file still matches the prepared snapshot, and use atomic per-file replacement. If a later write fails, attempted files are rolled back only when their content still matches the attempted cloud write, so apply or rollback cannot erase a concurrent local edit. Capture bounds each local file, total raw input, file count, and JSON depth before materializing a bundle; protocol bundles are limited to 1 MiB.

After a pull, normal Kompile precedence still applies:

1. Explicit command-line flags
2. Environment variables
3. Project-local settings
4. User-local settings
5. Built-in defaults

The cloud profile is not an additional runtime precedence layer; it changes local files only when `pull` is requested.

## Revisions and conflicts

Every profile has a monotonically increasing `version`. Push and delete use optimistic revision checks. Revision `-1` is the create-if-absent sentinel and is never a stored revision; this prevents two clients that both observed a missing profile from overwriting one another. By default, the CLI retrieves the current revision immediately before changing a profile. Automation can pin it explicitly:

```bash
kompile cloud settings push new-profile --expected-version -1
kompile cloud settings push default --expected-version 4
kompile cloud settings delete default --expected-version 5 --yes
```

A stale write returns a conflict without changing the profile. Retrieve the current profile, review it, and retry with the current revision.

## SaaS API

Authenticated `user` and `admin` JWTs can use:

```text
GET    /api/cli-settings
GET    /api/cli-settings/{profileKey}
PUT    /api/cli-settings/{profileKey}
DELETE /api/cli-settings/{profileKey}?expectedVersion=N
```

Profiles are scoped to the authenticated database user. A profile belonging to another user is indistinguishable from a missing profile. List responses contain metadata only; `GET` returns the settings bundle. The server bounds request bytes and JSON depth before DTO materialization, then limits profile count, file count, bundle depth, and stored payload size.
