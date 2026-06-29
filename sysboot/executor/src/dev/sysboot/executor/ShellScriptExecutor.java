package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShellScriptExecutor {

  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

  private final ShellRunner shellRunner;
  private final BinaryDownloadClient downloadClient;
  private final SensitiveTextRedactor redactor;

  public ShellScriptExecutor(ShellRunner shellRunner) {
    this(shellRunner, new HttpBinaryDownloadClient());
  }

  ShellScriptExecutor(ShellRunner shellRunner, BinaryDownloadClient downloadClient) {
    this.shellRunner = shellRunner;
    this.downloadClient = downloadClient;
    this.redactor = new SensitiveTextRedactor();
  }

  public StepResult execute(ShellScriptModule module) {
    boolean failed = false;
    for (ShellScriptItem item : module.items()) {
      StepResult result = executeItem(item);
      if (result instanceof StepResult.Failure && !module.continueOnError()) {
        return result;
      }
      failed = failed || result instanceof StepResult.Failure;
    }
    return failed
        ? new StepResult.Failure(
            module.name().value(), "One or more shell scripts failed", 1, Duration.ZERO)
        : new StepResult.Success(module.name().value(), Duration.ZERO);
  }

  public List<String> commandPreview(ShellScriptItem item) {
    return redactor.redactCommand(buildCommand("<interpreter>", Path.of(item.key()), item.args(), item.sudo()), item.environment());
  }

  private StepResult executeItem(ShellScriptItem item) {
    if (item.creates().filter(Files::exists).isPresent() || unlessMatches(item)) {
      return new StepResult.Skipped(item.name(), "idempotency guard matched");
    }
    Path scriptPath = scriptPath(item);
    ensureExecutable(scriptPath);

    String interpreter = detectInterpreter(scriptPath);
    List<String> command = buildCommand(interpreter, scriptPath, item.args(), item.sudo());
    Map<String, String> env = buildEnv(item);

    ProcessResult result = shellRunner.run(command, env, item.timeout());
    if (item.allowsExitCode(result.exitCode())) {
      return new StepResult.Success(item.name(), result.elapsed());
    }
    return new StepResult.Failure(
        item.name(),
        redactor.redact(result.stdout() + result.stderr(), item.environment()),
        result.exitCode(),
        result.elapsed());
  }

  private Path scriptPath(ShellScriptItem item) {
    return item.script().map(script -> script.value()).orElseGet(() -> download(item.url().orElseThrow()));
  }

  private Path download(URI url) {
    try {
      Path tempFile = Files.createTempFile("fluxion-script-", ".sh");
      downloadClient.downloadToFile(url, tempFile);
      return tempFile;
    } catch (IOException e) {
      throw new ShellExecutionException("Cannot download script: " + url, e);
    }
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

  private List<String> buildCommand(String interpreter, Path script, List<String> args, boolean sudo) {
    List<String> command = new ArrayList<>();
    if (sudo) {
      command.add("sudo");
    }
    command.add(interpreter);
    command.add(script.toString());
    command.addAll(args);
    return List.copyOf(command);
  }

  private Map<String, String> buildEnv(ShellScriptItem item) {
    var values = new java.util.LinkedHashMap<String, String>();
    item.environment().forEach(variable -> values.put(variable.name(), variable.value()));
    item.workingDir().ifPresent(dir -> values.put("PWD", dir.toString()));
    return Map.copyOf(values);
  }

  private boolean unlessMatches(ShellScriptItem item) {
    return item.unless()
        .map(command -> shellRunner.run(List.of("/bin/bash", "-lc", command), buildEnv(item), CHECK_TIMEOUT).isSuccess())
        .orElse(false);
  }
}
