# Development

Work from the active project root:

```bash
cd sysboot
```

Use Java/GraalVM 25. The local native-image smoke path has been validated with GraalVM CE 25.0.2.

Common commands:

```bash
mill __.compile
mill __.test
mill cli.assembly
mill cli.nativeImage
```

From the repository root, the `justfile` provides convenience gates:

```bash
just format
just lint
just verify
just native
```

## Coding Rules

Keep module boundaries intact and wire collaborators in `ApplicationContext`. Use constructor
injection, immutable records/value objects, and specific exception translation at layer boundaries.
Public methods should not return `null`.

When adding YAML fields or module types, update DTOs, mapper logic, domain records, tests,
`docs/config-schema.md`, and GraalVM reflection metadata together.
