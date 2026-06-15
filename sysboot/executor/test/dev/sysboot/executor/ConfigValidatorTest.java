package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
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

  private static BootstrapConfig config(Phase phase) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(new OsTarget.FedoraTarget("44"))
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
