# Native Image

Fluxion targets Linux GraalVM 25 native-image builds through Mill's `NativeImageModule`:

```bash
cd sysboot
./mill cli.nativeImage
```

The output binary is:

```text
out/cli/nativeImage.dest/native-executable
```

## Build Contract

The `cli` module declares `extends: [JavaModule, NativeImageModule]` and
`jvmVersion: graalvm-community:25`. The task invokes `native-image` with:

- `--no-fallback`
- GraalVM configuration from `graal/` via `-H:ConfigurationFileDirectories`
- HTTP and HTTPS URL protocol support for binary downloads
- SLF4J, Logback, and JLine runtime initialization

GraalVM CE 25.0.2 is the validated local toolchain. Mill resolves it from
`jvmVersion: graalvm-community:25`; the system `native-image` does not need to be on `PATH`. By
default the binary is dynamically linked against the host Linux C library. On mainstream Linux
distributions this usually means glibc. A fully static or musl binary is not currently configured.
Mill writes the task artifact as `native-executable`; release packaging renames it to `fluxion`.

## Reflection And Resources

Jackson YAML DTOs and state DTOs require reflection metadata. Picocli command classes are also
registered explicitly so native CLI parsing does not depend on runtime discovery. The resource
config includes Logback and SLF4J service-loader metadata so the native binary does not emit
provider warnings at startup.

When adding reflective types:

1. Add focused JVM tests for the path.
2. Update `graal/reflect-config.json` or regenerate with the native-image agent.
3. Rebuild with `./mill cli.nativeImage`.
4. Smoke test `--help`, `--version`, and `validate`.

## Smoke Test

```bash
./out/cli/nativeImage.dest/native-executable --help
./out/cli/nativeImage.dest/native-executable --version
./out/cli/nativeImage.dest/native-executable validate -c config/example-fedora.yaml --no-tui
```

## Troubleshooting

Missing reflection errors usually mean a new DTO, command, or nested command was added without
native metadata. Missing resource errors usually mean a Logback or configuration resource needs an
entry in `graal/resource-config.json`.
