package dev.sysboot.executor;

import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolchainKind;
import dev.sysboot.core.ToolchainModule;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolchainExecutor {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);

  private final ShellRunner shellRunner;

  public ToolchainExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(ToolchainModule module) {
    Path scriptPath = null;
    try {
      scriptPath = downloadToTemp(module.installScript(), module.name().value());
      Map<String, String> env = buildEnv(module.kind());
      List<String> command = buildCommand(scriptPath, module.installArgs());

      var result = shellRunner.run(command, env, INSTALL_TIMEOUT);

      return result.exitCode() == 0
          ? new StepResult.Success(module.name().value(), result.elapsed())
          : new StepResult.Failure(
              module.name().value(), result.stderr(), result.exitCode(), result.elapsed());
    } catch (IOException e) {
      return new StepResult.Failure(
          module.name().value(),
          "Failed to prepare install script: " + e.getMessage(),
          1,
          Duration.ZERO);
    } finally {
      if (scriptPath != null) {
        try {
          Files.deleteIfExists(scriptPath);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private List<String> buildCommand(Path script, List<String> extraArgs) {
    List<String> cmd = new ArrayList<>();
    cmd.add("sh");
    cmd.add(script.toString());
    cmd.addAll(extraArgs);
    return List.copyOf(cmd);
  }

  private Map<String, String> buildEnv(ToolchainKind kind) {
    String home = System.getProperty("user.home");
    return switch (kind) {
      case RUSTUP ->
          Map.of(
              "RUSTUP_INIT_SKIP_PATH_CHECK", "yes",
              "CARGO_HOME", home + "/.cargo",
              "RUSTUP_HOME", home + "/.rustup");
      case SDKMAN -> Map.of("SDKMAN_DIR", home + "/.sdkman");
      default -> Map.of();
    };
  }

  private Path downloadToTemp(String url, String prefix) throws IOException {
    try {
      var client = HttpClient.newHttpClient();
      var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
      }
      Path tmp = Files.createTempFile("sysboot-" + prefix + "-", ".sh");
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
