package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.Main;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.executor.JsonStateRepository;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportCommandResumeTest {

  @Test
  void reportLast_withNextPlanEntry_usesQuotedFullResumeCommandInEveryFormat(@TempDir Path tempDir)
      throws IOException {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      String profile = "team-profile";
      Path config = writeConfigWithSpaces(tempDir);
      new JsonStateRepository(new ObjectMapper()).save(state(profile));

      CliResult markdown = execute("report", "last", "-c", config.toString(), "--profile", profile);
      CliResult html =
          execute(
              "report", "last", "-c", config.toString(), "--profile", profile, "--format", "html");

      String command =
          "fluxion apply --no-tui -c '"
              + config
              + "' --profile team-profile --skip-already-installed --from-phase base";
      assertThat(markdown.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(markdown.stdout()).contains(command).doesNotContain("--from-phase desktop");
      assertThat(html.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(html.stdout()).contains(command).doesNotContain("--from-phase desktop");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  private BootstrapState state(String profile) {
    Instant completed = Instant.parse("2026-06-01T10:00:00Z");
    return new BootstrapState(
        profile,
        completed,
        "1.0.0",
        List.of(),
        List.of(
            new PhaseStateEntry("base", PhaseStatus.COMPLETED, completed),
            new PhaseStateEntry("desktop", PhaseStatus.COMPLETED, completed)),
        List.of(),
        Optional.of("tools"),
        Optional.empty(),
        Optional.empty());
  }

  private Path writeConfigWithSpaces(Path tempDir) throws IOException {
    Path directory = Files.createDirectories(tempDir.resolve("config directory"));
    Path config = directory.resolve("dependent profile.yaml");
    Files.writeString(
        config,
        """
        profile: report-test
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: packages
                name: tools
                packageManager: dnf
                packages: [git]
          - name: desktop
            dependsOn: [base]
            steps:
              - type: flatpak
                name: apps
                remote: flathub
                appIds: [org.mozilla.firefox]
        """);
    return config;
  }

  private CliResult execute(String... args) {
    var commandLine = Main.commandLine();
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));
    int exitCode = commandLine.execute(args);
    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
