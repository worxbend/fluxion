# Release

Use this checklist for Linux binary releases.

1. Run `just verify` from the repository root.
   This includes `just ci-policy-check`, which checks action pins, workflow permissions, release-tag
   binding, and the Mill launcher trust policy.
2. Run `cd sysboot && ./mill cli.assembly`.
3. Run `cd sysboot && ./mill cli.nativeImage`.
4. Smoke test the native binary:

   ```bash
   ./out/cli/nativeImage.dest/native-executable --help
   ./out/cli/nativeImage.dest/native-executable --version
   ./out/cli/nativeImage.dest/native-executable validate -c config/example-fedora.yaml --no-tui
   ./out/cli/nativeImage.dest/native-executable generate --os fedora --profile smoke --output /tmp/fluxion-smoke.yaml --force
   ./out/cli/nativeImage.dest/native-executable doctor -c /tmp/fluxion-smoke.yaml --skip-network
   ```

5. Record the Java, GraalVM, Mill, and host distribution versions.
6. Package `out/cli/nativeImage.dest/native-executable` as `fluxion` with README and example configs.

The release workflow is rerunnable only for the same source commit. If the requested tag already
exists and resolves to another commit, publishing stops before the GitHub release is updated.

GitHub Actions are pinned to immutable commit SHAs. Keep the major-version comment on each `uses`
line when updating a pin so dependency automation can continue tracking its release line.

When updating `.mill-version`, update every platform digest in `sysboot/mill`. Download the JVM,
Linux AMD64, Linux ARM64, macOS AMD64, and macOS ARM64 `mill-dist` artifacts from Maven Central,
verify them against the upstream checksum or signature, and record their SHA-256 values before
running `just ci-policy-check`.

The current native binary is dynamically linked against the build host's Linux C library. Build on
the oldest supported target distribution when broad glibc compatibility matters.
