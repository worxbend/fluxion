# Contributing to sysboot

## Code Style

All code must follow the constraints in `SKILL.md`:

- **Java 25** — use records, sealed interfaces, pattern matching, text blocks freely.
- Methods ≤ 20 lines, classes ≤ 300 lines (infrastructure), ≤ 200 lines (domain).
- No `null` returned from public methods — use `Optional<T>` or sealed `StepResult`.
- All collections returned must be unmodifiable (`List.of`, `List.copyOf`).
- Constructor injection only. No field injection, no setters.
- No `catch (Exception e)` — catch specific types and translate at layer boundaries.
- No comments that explain *what*; only *why* when non-obvious.

## Running Tests

```bash
# Run all tests
./mill __.test

# Run a specific module's tests
./mill core.test
./mill configParser.test
./mill executor.test

# Run a single test class
./mill executor.test.testOnly dev.sysboot.executor.DnfPackageInstallerTest
```

## Running the TUI Without Native Image

```bash
# Build a fat JAR and run
./mill cli.assembly
java -jar out/cli/assembly.dest/out.jar run -c config/example-fedora.yaml

# Plain stdout mode (no TUI dependency required)
java -jar out/cli/assembly.dest/out.jar run -c config/example-fedora.yaml --no-tui
```

## Building the Native Binary

```bash
# Requires GraalVM 25+ with native-image on PATH
./mill cli.nativeImage
# Output: out/cli/nativeImage.dest/sysboot
```

If native-image build fails on new reflective types, re-run with the agent:

```bash
java -agentlib:native-image-agent=config-output-dir=graal/ \
     -jar out/cli/assembly.dest/out.jar validate -c config/example-fedora.yaml
```

Then merge the generated `graal/` files and rebuild.

## PR Checklist

- [ ] All new public methods have unit tests.
- [ ] Test names follow `<scenario>_<condition>_<expectedResult>` convention.
- [ ] No class exceeds 300 lines.
- [ ] No method exceeds 20 lines.
- [ ] `./mill __.test` passes.
- [ ] `./mill configParser.test` covers any new YAML fields.
- [ ] New reflective types are registered in `graal/reflect-config.json`.
- [ ] `--no-tui` mode still works (no TamboUI imports in `executor` or `core`).
- [ ] Passwords never appear in log output.
- [ ] New package manager: `PackageManagerKind` updated, executor added, registered in `ApplicationContext`.
