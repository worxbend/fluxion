package dev.sysboot.executor;

import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class OhMyZshExecutor {

  private static final String INSTALLER_URL =
      "https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh";
  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);
  private static final String ITEM = "oh-my-zsh";

  private final ShellRunner shellRunner;

  public OhMyZshExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(OhMyZshModule module) {
    Path installerPath = null;
    try {
      installerPath = downloadToTemp(INSTALLER_URL);
      // RUNZSH=no and CHSH=no are mandatory — OMZ installer must not exec a new shell
      var env =
          Map.of(
              "RUNZSH", "no",
              "CHSH", "no",
              "HOME", System.getProperty("user.home"));
      var result = shellRunner.run(List.of("sh", installerPath.toString()), env, INSTALL_TIMEOUT);

      return result.exitCode() == 0
          ? new StepResult.Success(ITEM, result.elapsed())
          : new StepResult.Failure(ITEM, result.stderr(), result.exitCode(), result.elapsed());
    } catch (IOException e) {
      return new StepResult.Failure(
          ITEM, "Failed to download OMZ installer: " + e.getMessage(), 1, Duration.ZERO);
    } finally {
      if (installerPath != null) {
        try {
          Files.deleteIfExists(installerPath);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private Path downloadToTemp(String url) throws IOException {
    try {
      var client = HttpClient.newHttpClient();
      var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for " + url);
      }
      Path tmp = Files.createTempFile("sysboot-omz-", ".sh");
      try (InputStream in = response.body()) {
        Files.write(tmp, in.readAllBytes());
      }
      Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
      return tmp;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    }
  }
}
