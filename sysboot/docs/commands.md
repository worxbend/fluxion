# Command Reference

Fluxion commands are designed around a safe read-first workflow:

```bash
fluxion generate --os auto --profile starter --output ~/.config/fluxion/starter.yaml
fluxion validate -c ~/.config/fluxion/starter.yaml
fluxion lint -c ~/.config/fluxion/starter.yaml
fluxion doctor -c ~/.config/fluxion/starter.yaml
fluxion plan -c ~/.config/fluxion/starter.yaml
fluxion graph -c ~/.config/fluxion/starter.yaml --format mermaid
fluxion diff -c ~/.config/fluxion/starter.yaml
fluxion explain -c ~/.config/fluxion/starter.yaml --item git
fluxion snapshot --output ~/fluxion-snapshot.json
fluxion import packages --from-host --output ~/fluxion-packages.yaml
fluxion import flatpaks --from-host --output ~/fluxion-flatpaks.yaml
fluxion apply -c ~/.config/fluxion/starter.yaml
```

Global options:

```text
-c, --config=FILE    YAML profile path. Defaults to ~/.config/fluxion/default.yaml
--no-tui             Use plain stdout instead of the terminal UI
-v, --verbose        Reserved for more detailed output
-h, --help           Print help
--version            Print version
```

## `generate`

Creates a starter YAML profile without personal defaults.

```bash
fluxion generate --os auto --profile starter --preset developer --output ~/.config/fluxion/starter.yaml
```

Options:

```text
--os auto|fedora|arch|opensuse|debian
--profile NAME
--preset minimal|developer|desktop|dotfiles
--output PATH
--force
```

`--os auto` reads `/etc/os-release` and chooses the matching package manager. Generated configs
should still be reviewed before running.

## `snapshot`

Writes a review-required host inventory JSON file.

```bash
fluxion snapshot --output snapshot.json
fluxion snapshot --output snapshot.json --force
```

The snapshot is read-only. It records `/etc/os-release` fields, detected package-manager commands,
installed package names when the host package database is available, Flatpak apps/remotes when
Flatpak is present, default shell, and common toolchain presence. It does not read shell history,
dotfile contents, credentials, or other user secrets.

## `import`

Generates review-required profile fragments from the current host.

```bash
fluxion import packages --from-host --output packages.yaml
fluxion import packages --from-host --output packages.yaml --force
fluxion import flatpaks --from-host --output flatpaks.yaml
```

Package import reads the local package database and writes a YAML fragment with one `packages`
step. It detects RPM, Pacman, and Dpkg package databases and chooses `dnf`, `zypper`, `pacman`, or
`apt` as the package manager. The generated file is intentionally not applied automatically; review
it to remove transient, machine-specific, or unwanted packages before merging it into a profile.

Flatpak import reads installed app IDs with `flatpak list --app` and writes one `flatpak` step. It
uses `flathub` when that remote exists, otherwise the first configured remote, and fails clearly
when Flatpak is unavailable or no Flatpak apps are installed.

## `validate`

Loads YAML, maps it into the domain model, checks the phase dependency graph, and reports
configuration diagnostics.

```bash
fluxion validate -c config/example-fedora.yaml
fluxion validate -c config/example-fedora.yaml --strict
fluxion validate -c config/example-fedora.yaml --format json
```

Use this before `apply`, especially after editing dependencies, module names, URLs, or package
lists. Warnings do not fail validation unless `--strict` is set. JSON output is stable enough for
shell automation and CI checks.

## `lint`

Scores profile quality and reports advisory findings.

```bash
fluxion lint -c config/example-fedora.yaml
fluxion lint -c config/example-fedora.yaml --format json
```

`lint` is intentionally separate from `validate`: validation checks correctness, while lint flags
profile quality and safety concerns. The first rule set warns about compiled binaries without
checksums, shell command modules without probes, potentially destructive shell commands, downloaded
content piped into a shell, and embedded `sudo` inside shell commands.

## `doctor`

Checks host readiness for a profile.

```bash
fluxion doctor -c config/example-fedora.yaml
fluxion doctor -c config/example-fedora.yaml --skip-network
```

Checks include:

- config readability and parsing
- host OS detection
- writable state directory
- sudo command presence
- configured package-manager commands
- Flatpak command and Flathub reminder
- configured shell paths
- compiled-binary artifact formats
- compiled-binary URL reachability unless `--skip-network` is used

Any failed check returns exit code `5`. Warnings are printed but do not fail the command.

## `plan`

Prints the phase-ordered execution plan.

```bash
fluxion plan -c config/example-fedora.yaml
fluxion plan -c config/example-fedora.yaml --skip-already-installed
fluxion plan -c config/example-fedora.yaml --show-commands
fluxion plan -c config/example-fedora.yaml --format table
fluxion plan -c config/example-fedora.yaml --format tree
fluxion plan -c config/example-fedora.yaml --format json
```

The plan uses the same phase ordering as execution and shows restart checkpoints. With
`--skip-already-installed`, Fluxion probes configured items and marks what would be skipped.
`--show-commands` prints executor command previews for items where Fluxion can derive one.
`--format table` is compact for scanning, `--format tree` preserves phase/module hierarchy, and
JSON output includes phases, modules, item keys, item types, package managers, status labels, and
command previews where available.

## `graph`

Renders the phase dependency graph without probing or mutating the host.

```bash
fluxion graph -c config/example-fedora.yaml
fluxion graph -c config/example-fedora.yaml --format dot
fluxion graph -c config/example-fedora.yaml --format json
```

The default format is Mermaid `flowchart TD`, suitable for pasting into Markdown renderers that
support Mermaid. `dot` output can be rendered with Graphviz. JSON output includes phase nodes and
dependency edges for scripts.

## `diff`

Shows the configured work that differs from the current host and saved state.

```bash
fluxion diff -c config/example-fedora.yaml
fluxion diff -c config/example-fedora.yaml --format json
```

`diff` is read-only. It reuses the same live probes and state comparison as `status`, but omits
items already classified as installed. Output includes missing configured items, state-only entries,
unknown probe results, and version drift.

## `explain`

Explains why a phase or item would run, skip, or need review.

```bash
fluxion explain -c config/example-fedora.yaml --phase system-foundation
fluxion explain -c config/example-fedora.yaml --item git
fluxion explain -c config/example-fedora.yaml --item git --format json
```

`explain` is read-only. It reports phase dependencies, restart effect, module ownership, current
status classification, and command preview when the executor can provide one.

## `apply`

Executes a profile.

```bash
fluxion apply -c config/example-fedora.yaml
fluxion apply -c config/example-fedora.yaml --no-tui --skip-already-installed
fluxion apply -c config/example-fedora.yaml --dry-run
```

Useful options:

```text
--phase PHASE[,PHASE]       Run only selected phases
--from-phase PHASE          Resume from a specific phase
--dry-run                   Emit dry-run events instead of executing
--yes, -y                   Approve unattended execution
--skip-already-installed    Use state and probes to skip known installed work
--re-probe                  Ignore state and rely on live probes
--probe-only                Probe configured items without installing
--profile PROFILE           State profile name
```

When a phase uses `restartPolicy: prompt-logout`, Fluxion records completed state, prints a resume
command, and stops cleanly.

`run` remains available as an alias for `apply` for older scripts.

## `dry-run`

Runs the orchestrator dry-run path for the full config.

```bash
fluxion dry-run -c config/example-fedora.yaml
```

Prefer `plan` when you want a concise phase view. Prefer `apply --dry-run` when you want execution
events shaped like a real run.

## `status`

Reports live probe status for configured items.

```bash
fluxion status -c config/example-fedora.yaml
fluxion status -c config/example-fedora.yaml --format json
fluxion status -c config/example-fedora.yaml --summary
fluxion status -c config/example-fedora.yaml --missing
fluxion status -c config/example-fedora.yaml --state-only
fluxion status -c config/example-fedora.yaml --failed
fluxion status -c config/example-fedora.yaml --version-drift
fluxion status -c config/example-fedora.yaml --resume-command
fluxion status -c config/example-fedora.yaml --resume-command --format json
```

`status` compares configured items, live probes, and saved state. It reports configured installed
items, configured missing items, state-only entries, unknown probe results, and version drift when
state and live versions disagree. `--failed` shows missing, unknown, and version-drift items.
`--version-drift` narrows output to only items whose state version differs from the live probe.
`--resume-command` prints the command for the next incomplete phase based on saved state.

## `state`

Manages persisted state under `~/.local/share/fluxion`.

```bash
fluxion state show default
fluxion state show default --format json
fluxion state show -c config/example-fedora.yaml default
fluxion state path default
fluxion state forget --profile default --item git
fluxion state forget --profile default --phase shell-foundation
fluxion state reset default --force
```

State records successful items and phase completion fingerprints. A completed phase is skipped only
when its stored fingerprint still matches the current config. When `state show` is given `-c`, it
also prints `nextPhase`/`Next phase` for the first configured phase that is not completed in state.
JSON output includes a phase `reason` when Fluxion has recorded why a phase failed or was blocked.

## `list`

Prints configured modules and their item counts or source paths.

```bash
fluxion list -c config/example-fedora.yaml
fluxion list -c config/example-fedora.yaml --format json
```
