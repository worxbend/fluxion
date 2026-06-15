package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

  private final ConfigValidator validator = new ConfigValidator();

  @Test
  void validate_whenDependencyMissing_reportsPathAwareError() {
    ValidationReport report = validator.validate(config(phase("base", List.of(), "missing")));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs");
              assertThat(issue.message()).contains("missing");
            });
  }

  @Test
  void validate_whenPackageManagerDoesNotMatchTarget_reportsError() {
    var module = packageModule(PackageManagerKind.APT, "git");

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].packageManager");
              assertThat(issue.message()).contains("apt").contains("fedora");
            });
  }

  @Test
  void validate_whenCompiledBinaryHasNoChecksum_reportsWarning() throws Exception {
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
            Optional.empty(),
            Optional.empty(),
            Path.of("/usr/local/bin/rg"),
            false);

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isFalse();
    assertThat(report.hasWarnings()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].checksum");
              assertThat(issue.message()).contains("no checksum");
            });
  }

  @Test
  void validate_whenCompiledBinaryArchiveUnsupported_reportsError() throws Exception {
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(new URI("https://example.test/rg.zip")),
            Optional.empty(),
            Optional.empty(),
            Path.of("/usr/local/bin/rg"),
            false);

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].url");
              assertThat(issue.message()).contains("unsupported artifact format");
            });
  }

  @Test
  void validate_whenCompiledBinaryHasChecksumUrl_doesNotReportMissingChecksum() throws Exception {
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
            Optional.empty(),
            Optional.of(new BinaryUrl(new URI("https://example.test/rg.sha256"))),
            Path.of("/usr/local/bin/rg"),
            false);

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.issues())
        .noneMatch(issue -> issue.path().equals("jobs[0].steps[0].checksum"));
  }

  @Test
  void validate_whenCompiledBinaryHasChecksumAndChecksumUrl_reportsError() throws Exception {
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
            Optional.of(new Checksum("SHA-256", "a".repeat(64))),
            Optional.of(new BinaryUrl(new URI("https://example.test/rg.sha256"))),
            Path.of("/usr/local/bin/rg"),
            false);

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].checksum");
              assertThat(issue.message()).contains("either checksum or checksumUrl");
            });
  }

  @Test
  void validate_whenCompiledBinaryChecksumAlgorithmUnsupported_reportsError() throws Exception {
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
            Optional.of(new Checksum("SHA-1", "a".repeat(40))),
            Optional.empty(),
            Path.of("/usr/local/bin/rg"),
            false);

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].checksum.algorithm");
              assertThat(issue.message()).contains("unsupported checksum algorithm");
            });
  }

  @Test
  void validate_whenPackageModuleHasDuplicatePackage_reportsWarning() {
    var module = packageModule(PackageManagerKind.DNF, "git", "git");

    ValidationReport report = validator.validate(config(phase("base", List.of(module))));

    assertThat(report.hasErrors()).isFalse();
    assertThat(report.hasWarnings()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].packages");
              assertThat(issue.message()).contains("Duplicate package 'git'");
            });
  }

  @Test
  void validate_whenAptRepositoryOnDebianWithSigningKey_reportsNoIssues() {
    var module =
        new AptRepositoryModule(
            new ModuleName("docker"),
            "deb [signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian"
                + " bookworm stable",
            Path.of("/etc/apt/sources.list.d/docker.list"),
            Optional.of(URI.create("https://download.docker.com/linux/debian/gpg")),
            Optional.of(Path.of("/etc/apt/keyrings/docker.gpg")));

    ValidationReport report =
        validator.validate(
            config(new OsTarget.DebianTarget("12"), phase("repos", List.of(module))));

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void validate_whenAptRepositoryOnFedora_reportsError() {
    var module =
        new AptRepositoryModule(
            new ModuleName("docker"),
            "deb https://download.docker.com/linux/debian bookworm stable",
            Path.of("/etc/apt/sources.list.d/docker.list"),
            Optional.empty(),
            Optional.empty());

    ValidationReport report = validator.validate(config(phase("repos", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].type");
              assertThat(issue.message()).contains("APT repositories");
            });
  }

  @Test
  void validate_whenRpmRepositoryOnFedoraWithGpgKey_reportsNoIssues() {
    var module =
        new RpmRepositoryModule(
            new ModuleName("docker"),
            "docker",
            URI.create("https://download.docker.com/linux/fedora/$releasever/stable"),
            Path.of("/etc/yum.repos.d/docker.repo"),
            Optional.of(URI.create("https://download.docker.com/linux/fedora/gpg")),
            true,
            true);

    ValidationReport report = validator.validate(config(phase("repos", List.of(module))));

    assertThat(report.issues()).isEmpty();
  }

  @Test
  void validate_whenRpmRepositoryOnDebian_reportsError() {
    var module =
        new RpmRepositoryModule(
            new ModuleName("docker"),
            "docker",
            URI.create("https://download.docker.com/linux/fedora/$releasever/stable"),
            Path.of("/etc/yum.repos.d/docker.repo"),
            Optional.empty(),
            true,
            false);

    ValidationReport report =
        validator.validate(
            config(new OsTarget.DebianTarget("12"), phase("repos", List.of(module))));

    assertThat(report.hasErrors()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.ERROR);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].type");
              assertThat(issue.message()).contains("RPM repositories");
            });
  }

  @Test
  void validate_whenRpmRepositoryChecksGpgWithoutKey_reportsWarning() {
    var module =
        new RpmRepositoryModule(
            new ModuleName("docker"),
            "docker",
            URI.create("https://download.docker.com/linux/fedora/$releasever/stable"),
            Path.of("/etc/yum.repos.d/docker.repo"),
            Optional.empty(),
            true,
            true);

    ValidationReport report = validator.validate(config(phase("repos", List.of(module))));

    assertThat(report.hasWarnings()).isTrue();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ValidationIssue.Severity.WARNING);
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].gpgKeyUrl");
              assertThat(issue.message()).contains("no GPG key URL");
            });
  }

  private static BootstrapConfig config(Phase phase) {
    return config(new OsTarget.FedoraTarget("44"), phase);
  }

  private static BootstrapConfig config(OsTarget target, Phase phase) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(target)
        .addPhase(phase)
        .build();
  }

  private static Phase phase(String name, List<BootstrapModule> modules, String... dependencies) {
    return new Phase(
        new PhaseName(name),
        "",
        modules,
        List.of(dependencies).stream().map(PhaseName::new).toList(),
        new RestartPolicy.None());
  }

  private static PackageModule packageModule(PackageManagerKind manager, String... packages) {
    return new PackageModule(
        new ModuleName("packages"),
        manager,
        List.of(packages).stream().map(PackageName::new).toList(),
        true);
  }
}
