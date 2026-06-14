# TODO.md

Project follow-up list captured from initial analysis.

## Quality Checks

- Run `mill __.test` from `sysboot/` once Mill and Java 24 are available.
- Run `mill cli.assembly` and smoke-test `validate`, `list`, and `dry-run`.
- Check `mill cli.nativeImage` on GraalVM 24+ before publishing binary instructions.
- Check `gh auth status` before starting chezmoi research; `gh` and `jq` are installed, but auth state has not been verified.

## Potential Fixes

- Replace nullable `tuiApp` in `ApplicationContext` with an optional or split context model.
- Review `StateEntry` usage and remove nullable fields if the domain contract allows it.
- Verify `dry-run` package commands respect each module's configured package manager.
- Confirm docs reflect all module DTOs currently present in `config-parser`, including shell/tooling modules beyond the basic README examples.
- Wire `PhaseExecutionPlanner` into execution or remove/defer phase support until it is executable.
- Add orchestrator and probe handling for parsed module types: dotbot, default-shell, oh-my-zsh, toolchain, nerd-fonts, and shell-reload.
- Add tests for phase parsing, phase dependency ordering, restart policy events, and blocked-phase behavior.
- Reconcile current package layout with the master prompt's intended track/port layout. The prompt expects cleaner ports in `core`, a state track, `ModuleExecutor` registry, and `OutputAdapter` separation.
- Add missing `plan` command and fuller `state` subcommands if continuing toward the master prompt.

## Maintenance

- Keep GraalVM config updated when adding DTOs, Picocli commands, or resources.
- Add tests for each new public behavior, especially parser and executor changes.
- Preserve module dependency direction: `cli -> app -> tui -> executor -> config-parser -> core`.

## Chezmoi Research

- Run the user-provided `gh` CLI research plan under `~/.local/share/sysboot-research/chezmoi`.
- Produce `FINDINGS.md` with sections covering ordering, idempotency, shell restarts, sudo, errors, OS detection, UX, scope boundaries, and concrete sysboot recommendations.
- Produce standalone `RECOMMENDATIONS.md` extracted from the findings table.
