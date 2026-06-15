package dev.sysboot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.cli.Main;
import dev.sysboot.cli.error.ExitCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliReadOnlyCommandIT {

  @TempDir Path tempDir;

  @Test
  void readOnlyCommands_acceptGeneratedConfigAndJsonFormats() throws Exception {
    Path config = writeShellCommandConfig();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      assertSuccess(execute("validate", "--no-tui", "--format", "json", "-c", config.toString()));
      assertSuccess(execute("list", "--no-tui", "--format", "json", "-c", config.toString()));
      assertSuccess(execute("plan", "--no-tui", "--format", "json", "-c", config.toString()));
      assertSuccess(execute("status", "--format", "json", "-c", config.toString()));
      assertSuccess(execute("state", "show", "--format", "json", "default"));
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  private void assertSuccess(CliResult result) {
    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("\"profileName\"");
    assertThat(result.stderr()).isEmpty();
  }

  private CliResult execute(String... args) {
    CommandLine commandLine = Main.commandLine();
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));

    int exitCode = commandLine.execute(args);

    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private Path writeShellCommandConfig() throws Exception {
    Path config = tempDir.resolve("profile.yaml");
    Files.writeString(
        config,
        """
        profile: integration
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: shell-command
                name: echo
                shell: "/bin/sh"
                commands:
                  - "echo ready"
        """);
    return config;
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
