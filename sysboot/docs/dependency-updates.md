# Dependency Updates

Dependabot covers GitHub Actions in `.github/dependabot.yml`.

Mill YAML module coordinates in `sysboot/**/package.mill.yaml` are reviewed manually because they
are not represented as Maven `pom.xml` files. Use this cadence:

1. Weekly: review Maven Central releases for direct `mvnDeps` and `compileMvnDeps`.
2. Monthly: update non-critical libraries in one dependency-maintenance branch.
3. Immediately: update build, test, logging, compression, YAML, and CLI dependencies for security
   advisories.
4. Before merging dependency changes, run:

```bash
just format-check
cd sysboot
./mill __.test
./mill cli.assembly
./mill cli.nativeImage
```

Keep dependency updates isolated from feature changes unless a feature requires the version bump.
