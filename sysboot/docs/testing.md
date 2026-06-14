# Testing

Run all tests from `sysboot/`:

```bash
mill __.test
```

Focused commands:

```bash
mill core.test
mill configParser.test
mill executor.test
mill cli.test
mill executor.test.testOnly dev.sysboot.executor.DnfPackageInstallerTest
```

## Test Scope

Domain rules belong in low-level unit tests. Executor tests should use fake or mocked `ShellRunner`
instances so package-manager behavior is deterministic. CLI tests should execute Picocli through
`Main.commandLine()` and assert stdout, stderr, and exit codes.

Tests must not depend on the developer machine's installed packages unless they are explicitly
marked as integration tests and guarded by environment checks.
