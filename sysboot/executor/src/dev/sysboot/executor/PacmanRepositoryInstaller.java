package dev.sysboot.executor;

import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class PacmanRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final PacmanRepositoryConfigFiles configFiles;
  private final PrivilegedArtifactPublisher publisher;

  public PacmanRepositoryInstaller(ShellRunner shellRunner) {
    this(
        shellRunner,
        new DefaultPacmanRepositoryConfigFiles(),
        new PrivilegedAtomicFilePublisher(shellRunner));
  }

  PacmanRepositoryInstaller(ShellRunner shellRunner, PacmanRepositoryConfigFiles configFiles) {
    this(shellRunner, configFiles, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  PacmanRepositoryInstaller(
      ShellRunner shellRunner,
      PacmanRepositoryConfigFiles configFiles,
      PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.configFiles = configFiles;
    this.publisher = publisher;
  }

  public StepResult add(PacmanRepositoryModule module) {
    Path staged = null;
    try {
      String current = configFiles.readTrusted(module.configPath());
      ProcessResult probe = run(probeCommand(module));
      if (probe.exitCode() != 0 && probe.exitCode() != 1) {
        return failure(module, probe);
      }
      if (probe.exitCode() == 1) {
        String updated = withRepository(current, module);
        staged = configFiles.stage(updated);
        ProcessResult install =
            publisher.publish(
                staged,
                module.configPath(),
                "0644",
                ArtifactDigests.sha256(updated.getBytes(StandardCharsets.UTF_8)));
        if (!install.isSuccess()) {
          return failure(module, install);
        }
      }
      return result(module, run(refreshCommand()));
    } catch (IOException e) {
      return new StepResult.Failure(
          module.repositoryName(),
          "Refusing unsafe Pacman repository configuration",
          1,
          Duration.ZERO);
    } finally {
      delete(staged);
    }
  }

  List<String> probeCommand(PacmanRepositoryModule module) {
    return List.of(
        "grep", "-Fqx", "--", "[" + module.repositoryName() + "]", module.configPath().toString());
  }

  List<String> refreshCommand() {
    return List.of("sudo", "pacman", "-Sy");
  }

  private String withRepository(String current, PacmanRepositoryModule module) {
    String separator = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
    return current + separator + repositoryBlock(module);
  }

  private String repositoryBlock(PacmanRepositoryModule module) {
    var builder = new StringBuilder();
    builder.append('\n').append('[').append(module.repositoryName()).append("]\n");
    appendSetting(builder, module.enabled(), "Server = " + module.server());
    module
        .sigLevel()
        .ifPresent(value -> appendSetting(builder, module.enabled(), "SigLevel = " + value));
    module
        .include()
        .ifPresent(path -> appendSetting(builder, module.enabled(), "Include = " + path));
    return builder.toString();
  }

  private void appendSetting(StringBuilder builder, boolean enabled, String setting) {
    if (!enabled) {
      builder.append("# ");
    }
    builder.append(setting).append('\n');
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
  }

  private StepResult result(PacmanRepositoryModule module, ProcessResult process) {
    if (process.isSuccess()) {
      return new StepResult.Success(module.repositoryName(), process.elapsed());
    }
    return failure(module, process);
  }

  private StepResult failure(PacmanRepositoryModule module, ProcessResult process) {
    return new StepResult.Failure(
        module.repositoryName(),
        process.stdout() + process.stderr(),
        process.exitCode(),
        process.elapsed());
  }

  private void delete(Path path) {
    if (path == null) {
      return;
    }
    try {
      configFiles.deleteIfExists(path);
    } catch (IOException ignored) {
      // The command result remains authoritative.
    }
  }
}
