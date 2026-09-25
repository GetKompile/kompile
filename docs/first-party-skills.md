# First-party skills

Kompile does not require another vendor's CLI or skill directories to load skills.

- Repository-owned packages live in `skills/<name>/SKILL.md`, with supporting
  `references/`, scripts, and assets alongside the entry point.
- Both `build-dist.sh` and the Maven distribution assembly ship the complete tree
  into `<install>/lib/skills/`. `install.sh --dev` copies the same defaults.
- The shared skill loader used by chat and `skill_manager` reads bundled defaults
  from `KompileHome.installDirectory()`, including custom install locations.
- User definitions remain in `~/.kompile/skills/`; project definitions remain in
  `.kompile/skills/`. Both flat `<name>.md` and `<name>/SKILL.md` are supported.
  Distribution updates do not own these override directories.

Precedence, lowest to highest: bundled defaults, user vendor skills, user Kompile
skills, project vendor skills, project Kompile skills. Vendor scanning remains a
compatibility feature; `list_skills`, `get_skill`, and `expand_template` use the
combined registry. `scan_provider_skills` is specifically a project vendor scan,
not the complete registry.

Only repository-owned packages are shipped. Adding a skill to a vendor directory
or `~/.kompile/skills` on a developer machine does not publish it. To distribute
one, add a portable package to the repository's `skills/` tree, including all its
referenced files. Do not embed credentials or machine-specific absolute paths.
Currently that tree contains `kompile-orchestrator`; built-in commands such as
`/review` and `/test` are separately embedded in the CLI.

## Maintaining user and project skills

`skill_manager create_skill` accepts `layout=flat` (default, `<name>.md`) or
`layout=package` (`<name>/SKILL.md`). Set `project_scope=true` for project skills;
creation otherwise uses `~/.kompile/skills`. Add supporting references/scripts
with the file tools beneath the package directory.

`update_skill` detects either layout and changes only the entry point, leaving
supporting files intact. `delete_skill` removes the selected flat file or the
whole package, including references/scripts. For update/delete, an explicit
`project_scope=true|false` selects exactly that scope. If omitted, project wins,
then user; deletion no longer removes both scopes. Removing an override may expose
an underlying user/vendor/bundled definition again.

Mutations reject ambiguous flat/package duplicates and symlink entry points or
package roots. Package deletion checks the whole tree before removing any files
and rejects symlinks/special files. Bundled and vendor definitions are never
mutation targets; create an override instead. Package names must match their
directory names for management by name.

Checks:

- `python3 build-scripts/test_skill_distribution.py`
- Maven: `-pl :kompile-cli-main -Dtest=SkillRuntimeContractTest test`

A new release/reinstall is needed to deliver changed packaging to another machine.
