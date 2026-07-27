package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolPackageBackend;
import dev.sysboot.core.ToolPackagesModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Installs packages through an ecosystem tool.
 *
 * <p>Each package runs in its own process so one failure never blocks the rest, matching how {@code
 * packages} already behaves — a list of twenty crates where the third has been yanked should still
 * install the other nineteen.
 */
public final class ToolPackagesExecutor {

  private static final Duration TIMEOUT = Duration.ofMinutes(30);

  private final ShellRunner shellRunner;

  public ToolPackagesExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(ToolPackagesModule module) {
    if (!backendAvailable(module.backend())) {
      return new StepResult.Failure(
          module.name().value(),
          module.backend().binary()
              + " is not on PATH; install it before this step (see docs/config-schema.md)",
          1,
          Duration.ZERO);
    }
    var failures = new ArrayList<String>();
    for (ToolPackagesModule.ToolPackage pkg : module.packages()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      ProcessResult result = shellRunner.run(installCommand(module, pkg), Map.of(), TIMEOUT);
      if (!result.isSuccess()) {
        failures.add(pkg.name() + ": " + detail(result));
      }
    }
    if (!failures.isEmpty() && !module.continueOnError()) {
      return new StepResult.Failure(
          module.name().value(), String.join("; ", failures), 1, Duration.ZERO);
    }
    return new StepResult.Success(module.name().value(), Duration.ZERO);
  }

  public List<String> commandPreview(ToolPackagesModule module) {
    var preview = new ArrayList<String>();
    module.packages().forEach(pkg -> preview.addAll(installCommand(module, pkg)));
    return List.copyOf(preview);
  }

  private boolean backendAvailable(ToolPackageBackend backend) {
    return shellRunner
        .run(List.of("command", "-v", backend.binary()), Map.of(), Duration.ofSeconds(15))
        .isSuccess();
  }

  private List<String> installCommand(
      ToolPackagesModule module, ToolPackagesModule.ToolPackage pkg) {
    Optional<String> version = pkg.version();
    return switch (module.backend()) {
      case CARGO_BINSTALL ->
          version
              .map(v -> List.of("cargo-binstall", "--no-confirm", pkg.name() + "@" + v))
              .orElseGet(() -> List.of("cargo-binstall", "--no-confirm", pkg.name()));
      case CARGO ->
          version
              .map(v -> List.of("cargo", "install", "--locked", "--version", v, pkg.name()))
              .orElseGet(() -> List.of("cargo", "install", "--locked", pkg.name()));
      case SNAP ->
          version
              .map(v -> List.of("sudo", "snap", "install", pkg.name(), "--channel", v))
              .orElseGet(() -> List.of("sudo", "snap", "install", pkg.name()));
      case PIPX ->
          version
              .map(v -> List.of("pipx", "install", pkg.name() + "==" + v))
              .orElseGet(() -> List.of("pipx", "install", pkg.name()));
      case UV_TOOL ->
          version
              .map(v -> List.of("uv", "tool", "install", pkg.name() + "==" + v))
              .orElseGet(() -> List.of("uv", "tool", "install", pkg.name()));
      case NPM_GLOBAL ->
          version
              .map(v -> List.of("npm", "install", "-g", pkg.name() + "@" + v))
              .orElseGet(() -> List.of("npm", "install", "-g", pkg.name()));
      case GO_INSTALL -> List.of("go", "install", pkg.name() + "@" + version.orElse("latest"));
    };
  }

  private String detail(ProcessResult result) {
    String text = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return text.isBlank() ? "exit " + result.exitCode() : text.strip();
  }
}
