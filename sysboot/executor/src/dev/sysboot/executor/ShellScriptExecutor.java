package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShellScriptExecutor {

  private static final Duration SCRIPT_TIMEOUT = Duration.ofMinutes(30);

  private final ShellRunner shellRunner;

  public ShellScriptExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(ShellScriptModule module) {
    Path scriptPath = module.script().value();
    ensureExecutable(scriptPath);

    String interpreter = detectInterpreter(scriptPath);
    List<String> command = buildCommand(interpreter, scriptPath, module.args());
    Map<String, String> env = buildEnv(module);

    ProcessResult result = shellRunner.run(command, env, SCRIPT_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(module.name().value(), result.elapsed());
    }
    return new StepResult.Failure(
        module.name().value(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }

  private String detectInterpreter(Path script) {
    try {
      List<String> lines = Files.readAllLines(script);
      if (!lines.isEmpty() && lines.getFirst().startsWith("#!")) {
        return lines.getFirst().substring(2).strip().split("\\s+")[0];
      }
    } catch (IOException e) {
      // fall through to default
    }
    return "/bin/bash";
  }

  private void ensureExecutable(Path script) {
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(script);
      if (!perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, perms);
      }
    } catch (IOException e) {
      throw new ShellExecutionException("Cannot set executable permission on script: " + script, e);
    }
  }

  private List<String> buildCommand(String interpreter, Path script, List<String> args) {
    List<String> command = new ArrayList<>();
    command.add(interpreter);
    command.add(script.toString());
    command.addAll(args);
    return List.copyOf(command);
  }

  private Map<String, String> buildEnv(ShellScriptModule module) {
    return module.workingDir().map(dir -> Map.of("PWD", dir.toString())).orElse(Map.of());
  }
}
