package dev.sysboot.executor;

import dev.sysboot.core.ExecutionApproval;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShellScriptExecutor {

  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);
  private static final long MAX_SCRIPT_BYTES = 64L * 1024 * 1024;
  private static final int MAX_SHEBANG_BYTES = 4 * 1024;
  private static final Path PRIVILEGED_SCRIPT_ANCHOR = Path.of("/run/fluxion-script");

  private final ShellRunner shellRunner;
  private final ScriptDownloadClient downloadClient;
  private final SensitiveTextRedactor redactor;
  private final ExecutionApproval approval;
  private final PrivilegedArtifactPublisher publisher;

  public ShellScriptExecutor(ShellRunner shellRunner) {
    this(shellRunner, new VerifiedScriptDownloader(), ExecutionApproval.denyAll());
  }

  public ShellScriptExecutor(ShellRunner shellRunner, ExecutionApproval approval) {
    this(shellRunner, new VerifiedScriptDownloader(), approval);
  }

  ShellScriptExecutor(ShellRunner shellRunner, ScriptDownloadClient downloadClient) {
    this(shellRunner, downloadClient, ExecutionApproval.denyAll());
  }

  ShellScriptExecutor(
      ShellRunner shellRunner, ScriptDownloadClient downloadClient, ExecutionApproval approval) {
    this(shellRunner, downloadClient, approval, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  ShellScriptExecutor(
      ShellRunner shellRunner,
      ScriptDownloadClient downloadClient,
      ExecutionApproval approval,
      PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.downloadClient = downloadClient;
    this.approval = approval;
    this.publisher = publisher;
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
    return redactor.redactCommand(
        buildCommand("<interpreter>", Path.of(item.key()), item.args(), item.sudo()),
        item.environment());
  }

  StepResult executeItem(ShellScriptItem item) {
    return executeItem(item, approval);
  }

  StepResult executeItem(ShellScriptItem item, ExecutionApproval executionApproval) {
    Optional<StepResult> confirmationFailure = confirmationFailure(item, executionApproval);
    if (confirmationFailure.isPresent()) {
      return confirmationFailure.orElseThrow();
    }
    if (item.creates().filter(Files::exists).isPresent() || unlessMatches(item)) {
      return new StepResult.Skipped(item.name(), "idempotency guard matched");
    }
    Path scriptPath;
    try {
      scriptPath = scriptPath(item);
    } catch (ShellExecutionException e) {
      return new StepResult.Failure(
          item.name(), "Remote script download or SHA-256 verification failed", 1, Duration.ZERO);
    }
    return executePrepared(item, scriptPath);
  }

  private Optional<StepResult> confirmationFailure(
      ShellScriptItem item, ExecutionApproval executionApproval) {
    if (item.confirm().isEmpty()) {
      return Optional.empty();
    }
    var request =
        new ExecutionApproval.ConfirmationRequest(item.name(), item.confirm().orElseThrow());
    if (executionApproval.approve(request)) {
      return Optional.empty();
    }
    return Optional.of(
        new StepResult.Failure(
            item.name(),
            "Explicit confirmation required; rerun apply with --yes",
            2,
            Duration.ZERO));
  }

  private StepResult executePrepared(ShellScriptItem item, Path scriptPath) {
    try {
      if (item.sudo() && item.url().isPresent()) {
        return runPrivilegedRemoteScript(item, scriptPath);
      }
      return runScript(item, scriptPath);
    } catch (IOException | ShellExecutionException e) {
      return new StepResult.Failure(
          item.name(), "Script preparation failed: unsafe or unreadable script", 1, Duration.ZERO);
    } finally {
      deleteDownloaded(item, scriptPath);
    }
  }

  private StepResult runScript(ShellScriptItem item, Path scriptPath) {
    return resultFor(item, runProcess(item, scriptPath));
  }

  private StepResult runPrivilegedRemoteScript(ShellScriptItem item, Path scriptPath)
      throws IOException {
    var executed = new AtomicBoolean();
    ProcessResult result =
        publisher.consumeVerified(
            scriptPath,
            PRIVILEGED_SCRIPT_ANCHOR,
            "0555",
            item.sha256().orElseThrow(),
            staged -> {
              executed.set(true);
              return runProcess(item, staged);
            });
    if (!executed.get()) {
      return new StepResult.Failure(
          item.name(),
          "Script preparation failed: trusted root stage was rejected",
          1,
          Duration.ZERO);
    }
    return resultFor(item, result);
  }

  private ProcessResult runProcess(ShellScriptItem item, Path scriptPath) {
    String interpreter = detectInterpreter(scriptPath);
    List<String> command = buildCommand(interpreter, scriptPath, item.args(), item.sudo());
    Map<String, String> env = buildEnv(item);
    return ExecutionOutput.withSensitiveEnvironment(
        item.environment(), () -> shellRunner.run(command, env, item.workingDir(), item.timeout()));
  }

  private StepResult resultFor(ShellScriptItem item, ProcessResult result) {
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
    return item.script()
        .map(script -> script.value())
        .orElseGet(() -> download(item.url().orElseThrow(), item.sha256().orElseThrow()));
  }

  private Path download(URI url, dev.sysboot.core.Sha256Digest sha256) {
    try {
      return downloadClient.download(url, sha256);
    } catch (IOException e) {
      throw new ShellExecutionException("Cannot download or verify remote script", e);
    }
  }

  private void deleteDownloaded(ShellScriptItem item, Path scriptPath) {
    if (item.url().isEmpty()) {
      return;
    }
    try {
      Files.deleteIfExists(scriptPath);
    } catch (IOException ignored) {
      // The script result remains authoritative after best-effort temp cleanup.
    }
  }

  private String detectInterpreter(Path script) {
    try {
      requireBoundedRegularFile(script);
      String firstLine = readFirstLine(script);
      return interpreterFrom(firstLine).orElse("/bin/bash");
    } catch (IOException e) {
      throw new ShellExecutionException("Cannot safely inspect script", e);
    }
  }

  private void requireBoundedRegularFile(Path script) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(script, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.size() > MAX_SCRIPT_BYTES) {
      throw new ShellExecutionException("Script is not a bounded regular file");
    }
  }

  private String readFirstLine(Path script) throws IOException {
    try (InputStream input =
            Files.newInputStream(script, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var line = new ByteArrayOutputStream()) {
      int next;
      while (line.size() < MAX_SHEBANG_BYTES && (next = input.read()) >= 0) {
        if (next == '\n' || next == '\r') {
          break;
        }
        line.write(next);
      }
      return line.toString(StandardCharsets.UTF_8);
    }
  }

  private Optional<String> interpreterFrom(String firstLine) {
    if (!firstLine.startsWith("#!")) {
      return Optional.empty();
    }
    String declaration = firstLine.substring(2).strip();
    if (declaration.isEmpty()) {
      throw new ShellExecutionException("Script shebang does not name an interpreter");
    }
    return Optional.of(declaration.split("\\s+")[0]);
  }

  private List<String> buildCommand(
      String interpreter, Path script, List<String> args, boolean sudo) {
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
    return Map.copyOf(values);
  }

  private boolean unlessMatches(ShellScriptItem item) {
    return item.unless()
        .map(
            command ->
                ExecutionOutput.withSensitiveEnvironment(
                        item.environment(),
                        () ->
                            shellRunner.run(
                                List.of("/bin/bash", "-lc", command),
                                buildEnv(item),
                                item.workingDir(),
                                CHECK_TIMEOUT))
                    .isSuccess())
        .orElse(false);
  }
}
