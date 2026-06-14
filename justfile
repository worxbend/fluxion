set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

mill := env_var_or_default("MILL", "./mill")
gjf_version := "1.35.0"
gjf_jar := ".cache/google-java-format-1.35.0-all-deps.jar"

default: verify

# Print the local toolchain versions used by the project.
doctor:
    java -version
    cd sysboot && {{mill}} --version
    if command -v native-image >/dev/null 2>&1; then native-image --version; else echo "native-image: not installed"; fi
    if command -v just >/dev/null 2>&1; then just --version; else echo "just: not installed"; fi

# Compile every Mill module.
compile:
    cd sysboot && {{mill}} __.compile

# Run every test module.
test:
    cd sysboot && {{mill}} __.test

# Compile with the repo's -Xlint settings. This is the current Java lint gate.
lint: compile

# Download the pinned formatter jar used by format and format-check.
setup-format:
    mkdir -p .cache
    test -f {{gjf_jar}} || curl -fsSL \
      -o {{gjf_jar}} \
      https://github.com/google/google-java-format/releases/download/v{{gjf_version}}/google-java-format-{{gjf_version}}-all-deps.jar

# Format all Java sources.
format: setup-format
    java -jar {{gjf_jar}} -i $(find sysboot -path '*/out/*' -prune -o -name '*.java' -print)

# Check Java formatting without modifying files.
format-check: setup-format
    java -jar {{gjf_jar}} --dry-run --set-exit-if-changed \
      $(find sysboot -path '*/out/*' -prune -o -name '*.java' -print)

# Validate shipped example configs through the CLI.
validate-configs: compile
    cd sysboot && {{mill}} cli.run validate --no-tui -c config/example-fedora.yaml
    cd sysboot && {{mill}} cli.run validate --no-tui -c config/example-arch.yaml
    cd sysboot && {{mill}} cli.run validate --no-tui -c config/example-opensuse.yaml

# Run the normal local verification gate.
verify: compile test validate-configs

# CI verification gate. Kept separate so CI can add format-check explicitly.
ci: doctor verify

# Build the GraalVM native executable.
native:
    cd sysboot && {{mill}} cli.nativeImage

# Build native executable and run a basic smoke test.
native-smoke: native
    ./sysboot/out/cli/nativeImage.dest/native-executable --help >/dev/null

# Remove Mill outputs and local formatter cache.
clean:
    rm -rf sysboot/out .cache
