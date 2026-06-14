# Native Image

`sysboot` targets Linux GraalVM 25 native-image builds through the Mill task:

```bash
cd sysboot
mill cli.nativeImage
```

The output binary is:

```text
out/cli/nativeImage.dest/sysboot
```

## Build Contract

The task builds the CLI assembly JAR and invokes `native-image` with:

- `--no-fallback`
- explicit reflection and resource config files from `graal/`
- HTTP and HTTPS URL protocol support for binary downloads
- SLF4J, Logback, and JLine runtime initialization

GraalVM CE 25.0.2 is the validated local toolchain. By default the binary is dynamically linked
against the host Linux C library. On mainstream Linux distributions this usually means glibc. A
fully static or musl binary is not currently configured.

## Reflection And Resources

Jackson YAML DTOs and state DTOs require reflection metadata. Picocli command classes are also
registered explicitly so native CLI parsing does not depend on runtime discovery. The resource
config includes Logback and SLF4J service-loader metadata so the native binary does not emit
provider warnings at startup.

When adding reflective types:

1. Add focused JVM tests for the path.
2. Update `graal/reflect-config.json` or regenerate with the native-image agent.
3. Rebuild with `mill cli.nativeImage`.
4. Smoke test `--help`, `--version`, and `validate`.

## Smoke Test

```bash
./out/cli/nativeImage.dest/sysboot --help
./out/cli/nativeImage.dest/sysboot --version
./out/cli/nativeImage.dest/sysboot validate -c config/example-fedora.yaml --no-tui
```

## Troubleshooting

Missing reflection errors usually mean a new DTO, command, or nested command was added without
native metadata. Missing resource errors usually mean a Logback or configuration resource needs an
entry in `graal/resource-config.json`.
