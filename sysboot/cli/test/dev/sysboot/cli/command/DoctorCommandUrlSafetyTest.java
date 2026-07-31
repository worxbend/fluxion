package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.cli.error.CliExceptionHandler;
import dev.sysboot.cli.error.ExitCode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class DoctorCommandUrlSafetyTest {

  @TempDir Path tempDir;

  private String originalHome;

  @BeforeEach
  void useIsolatedHome() {
    originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
  }

  @AfterEach
  void restoreHome() {
    System.setProperty("user.home", originalHome);
  }

  @Test
  void doctor_whenNetworkSkipped_displaysPublicUrlWithoutSendingRequest() throws IOException {
    var called = new AtomicBoolean();
    var command =
        new DoctorCommand(
            uri -> {
              called.set(true);
              return 204;
            });

    CliResult result = execute(command, "--skip-network", "-c", writeBinaryConfig().toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("[warn] network")
        .contains("[warn] checksum network")
        .contains("https://example.test/rg.sha256");
    assertPublicUrlOnly(result.stdout());
    assertThat(called).isFalse();
  }

  @Test
  void doctor_whenNetworkSucceeds_sendsFullUriButDisplaysPublicUrl() throws IOException {
    var requested = new ArrayList<URI>();
    var command =
        new DoctorCommand(
            uri -> {
              requested.add(uri);
              return 204;
            });

    CliResult result = execute(command, "-c", writeBinaryConfig().toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("[pass] network")
        .contains("[pass] checksum network")
        .contains("https://example.test/rg.sha256");
    assertPublicUrlOnly(result.stdout());
    assertThat(requested)
        .containsExactly(
            URI.create(signedUrl()),
            URI.create(signedChecksumUrl()),
            URI.create("https://example.test/rg.tar.gz.asc"));
  }

  @Test
  void doctor_whenNetworkFails_doesNotExposeUriOrExceptionRequestData() throws IOException {
    var command =
        new DoctorCommand(
            uri -> {
              throw new IOException("request failed for " + uri);
            });

    CliResult result = execute(command, "-c", writeBinaryConfig().toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.EXTERNAL_DEPENDENCY_ERROR.value());
    assertThat(result.stdout()).contains("[fail] network").contains("request failed");
    assertPublicUrlOnly(result.stdout());
    assertThat(result.stderr()).contains("Doctor found").doesNotContain("query-secret");
  }

  @Test
  void doctor_whenBinaryUrlContainsUserInfo_reportsOnlyPublicUrl() throws IOException {
    Path config =
        writeBinaryConfig(
            "https://user:password@example.test/rg.tar.gz?token=query-secret#fragment-secret");

    CliResult result = execute(new DoctorCommand(), "--skip-network", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.EXTERNAL_DEPENDENCY_ERROR.value());
    assertThat(result.stdout()).contains("[fail] config file");
    assertPublicUrlOnly(result.stdout());
  }

  private Path writeBinaryConfig() throws IOException {
    return writeBinaryConfig(signedUrl());
  }

  private Path writeBinaryConfig(String url) throws IOException {
    Path config = tempDir.resolve("binary.yaml");
    Files.writeString(
        config,
        """
        profile: doctor-url-safety
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
                checksumUrl: "%s"
                signatureUrl: "https://example.test/rg.tar.gz.asc"
                allowedSignerFingerprint: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                installPath: /usr/local/bin/rg
                archivePath: rg
        """
            .formatted(url, signedChecksumUrl()));
    return config;
  }

  private String signedUrl() {
    return "https://example.test/rg.tar.gz?token=query-secret#fragment-secret";
  }

  private String signedChecksumUrl() {
    return "https://example.test/rg.sha256?checksum=checksum-secret#checksum-fragment";
  }

  private void assertPublicUrlOnly(String output) {
    assertThat(output)
        .contains("https://example.test/rg.tar.gz")
        .doesNotContain(
            "user:password",
            "password",
            "token",
            "query-secret",
            "fragment-secret",
            "checksum-secret",
            "checksum-fragment");
  }

  private CliResult execute(DoctorCommand command, String... args) {
    var handler = new CliExceptionHandler();
    var commandLine =
        new CommandLine(command)
            .setExecutionExceptionHandler(handler)
            .setParameterExceptionHandler(handler);
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));
    int exitCode = commandLine.execute(args);
    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
