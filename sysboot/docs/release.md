# Release

Use this checklist for Linux binary releases.

1. Run `just verify` from the repository root.
2. Run `cd sysboot && ./mill cli.assembly`.
3. Run `cd sysboot && ./mill cli.nativeImage`.
4. Smoke test the native binary:

   ```bash
   ./out/cli/nativeImage.dest/native-executable --help
   ./out/cli/nativeImage.dest/native-executable --version
   ./out/cli/nativeImage.dest/native-executable validate -c config/example-fedora.yaml --no-tui
   ```

5. Record the Java, GraalVM, Mill, and host distribution versions.
6. Package `out/cli/nativeImage.dest/native-executable` as `sysboot` with README and example configs.

The current native binary is dynamically linked against the build host's Linux C library. Build on
the oldest supported target distribution when broad glibc compatibility matters.
