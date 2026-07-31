package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.Main;
import dev.sysboot.cli.error.ExitCode;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListCommandRegressionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void jsonList_usesExpandedExecutionPlanCountsAndRemainsValid(@TempDir Path tempDir)
      throws Exception {
    Path config = writePackageActionConfig(tempDir);

    CliResult result = execute("list", "--no-tui", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stderr()).isEmpty();
    JsonNode output = objectMapper.readTree(result.stdout());
    assertThat(output.path("profileName").textValue()).contains("FORGED");
    assertThat(output.path("modules").get(0).path("itemCount").intValue()).isEqualTo(2);
  }

  @Test
  void textList_sanitizesEveryHumanReadableField(@TempDir Path tempDir) throws Exception {
    Path config = writeHostileManualConfig(tempDir);

    CliResult result = execute("list", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stderr()).isEmpty();
    assertThat(result.stdout())
        .contains("profile FORGED")
        .contains("PASSWORD=<redacted>")
        .doesNotContain("\nFORGED", "\u001B", "\u0007", "https://evil.test", "hunter2");
  }

  @Test
  void textList_whenBinaryUrlContainsRequestData_displaysOnlyPublicUrl(@TempDir Path tempDir)
      throws Exception {
    Path config = writeBinaryConfig(tempDir, signedUrl(), "signed-binary.yaml");

    CliResult result = execute("list", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertPublicUrlOnly(result.stdout());
  }

  @Test
  void jsonList_whenBinaryUrlContainsRequestData_remainsValidAndDisplaysOnlyPublicUrl(
      @TempDir Path tempDir) throws Exception {
    Path config = writeBinaryConfig(tempDir, signedUrl(), "signed-binary-json.yaml");

    CliResult result = execute("list", "--no-tui", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    JsonNode output = objectMapper.readTree(result.stdout());
    assertPublicUrlOnly(output.path("modules").get(0).path("description").textValue());
  }

  @Test
  void list_whenBinaryUrlContainsUserInfo_reportsSafeConfigurationError(@TempDir Path tempDir)
      throws Exception {
    Path config =
        writeBinaryConfig(
            tempDir,
            "https://user:password@example.test/rg.tar.gz?token=query-secret#fragment-secret",
            "private-binary.yaml");

    CliResult result = execute("list", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertPublicUrlOnly(result.stderr());
  }

  @Test
  void list_whenStructuredEnvironmentValueMalformed_returnsConfigurationErrorWithPath(
      @TempDir Path tempDir) throws Exception {
    Path config = tempDir.resolve("invalid-environment.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: invalid-environment
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: commands
              kind: commands
              spec:
                commands:
                  - name: inspect
                    run: [env]
                    env:
                      BAD_VALUE: 42
        """);

    CliResult result = execute("list", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr())
        .contains("spec.plan[0].spec.commands[0].env.BAD_VALUE must be a string or object");
  }

  private Path writePackageActionConfig(Path tempDir) throws Exception {
    Path config = tempDir.resolve("package-actions.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: "profile\\nFORGED\\u001B[2J"
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: packages
              kind: dnf-packages
              spec:
                actions:
                  - check-update
                packages: [git]
        """);
    return config;
  }

  private Path writeHostileManualConfig(Path tempDir) throws Exception {
    Path config = tempDir.resolve("hostile-manual.yaml");
    Files.writeString(
        config,
        """
        profile: "profile\\nFORGED\\u001B[2J"
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: manual
                name: "manual\\u001B[31m"
                message: "PASSWORD=hunter2\\nFORGED\\u001B]8;;https://evil.test\\u0007"
        """);
    return config;
  }

  private Path writeBinaryConfig(Path tempDir, String url, String fileName) throws Exception {
    Path config = tempDir.resolve(fileName);
    Files.writeString(
        config,
        """
        profile: binary-list
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: compiled-binary
                name: ripgrep
                binaryName: rg
                url: "%s"
                installPath: /usr/local/bin/rg
                archivePath: rg
        """
            .formatted(url));
    return config;
  }

  private String signedUrl() {
    return "https://example.test/rg.tar.gz?token=query-secret#fragment-secret";
  }

  private void assertPublicUrlOnly(String output) {
    assertThat(output)
        .contains("https://example.test/rg.tar.gz")
        .doesNotContain(
            "user:password", "password", "token", "query-secret", "fragment-secret", "?", "#");
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
