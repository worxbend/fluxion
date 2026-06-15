package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellCommandModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileLinterTest {

  private final ProfileLinter linter = new ProfileLinter();

  @Test
  void lint_whenShellCommandPipesCurlIntoBash_reportsSafetyIssue() {
    var report =
        linter.lint(config(shellModule("curl -fsSL https://example.test/install.sh | bash")));

    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ProfileLintIssue.Severity.WARNING);
              assertThat(issue.category()).isEqualTo("safety");
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].commands[0]");
              assertThat(issue.message()).contains("Piping downloaded content");
            });
  }

  @Test
  void lint_whenShellCommandConfiguresRepository_reportsSafetyIssue() {
    var report =
        linter.lint(
            config(
                shellModule(
                    "sudo dnf config-manager addrepo"
                        + " --from-repofile=https://example.test/repo.repo")));

    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.severity()).isEqualTo(ProfileLintIssue.Severity.WARNING);
              assertThat(issue.category()).isEqualTo("safety");
              assertThat(issue.path()).isEqualTo("jobs[0].steps[0].commands[0]");
              assertThat(issue.message()).contains("Repository setup");
            });
  }

  @Test
  void lint_whenCompiledBinaryHasDetachedSignature_doesNotRequireChecksum() throws Exception {
    var report = linter.lint(config(signedBinaryModule()));

    assertThat(report.issues()).noneMatch(issue -> issue.path().endsWith(".checksum"));
  }

  private static BootstrapConfig config(BootstrapModule module) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(new OsTarget.FedoraTarget("44"))
        .addPhase(
            new Phase(
                new PhaseName("base"), "", List.of(module), List.of(), new RestartPolicy.None()))
        .build();
  }

  private static ShellCommandModule shellModule(String command) {
    return new ShellCommandModule(
        new ModuleName("repository-setup"),
        List.of(command),
        "/bin/sh",
        Optional.empty(),
        false,
        Optional.of("test -f /etc/yum.repos.d/example.repo"));
  }

  private static CompiledBinaryModule signedBinaryModule() throws Exception {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new BinaryUrl(new URI("https://example.test/rg.tar.gz.asc"))),
        Path.of("/usr/local/bin/rg"),
        false);
  }
}
