package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DirectModuleExecutorFailFastTest {

  @Test
  void fileWrite_whenContinuationDisabled_stopsAfterFirstFailure() {
    FileWriteExecutor fileWrites = mock(FileWriteExecutor.class);
    FileWriteItem first = item("first");
    FileWriteItem second = item("second");
    when(fileWrites.write(first))
        .thenReturn(new StepResult.Failure(first.itemKey(), "failed", 1, Duration.ZERO));
    var module = new FileWriteModule(new ModuleName("files"), List.of(first, second), false);

    boolean failed =
        executor(fileWrites).execute(module, ignored -> {}, phaseExecutors(), runner());

    assertThat(failed).isTrue();
    verify(fileWrites).write(first);
    verify(fileWrites, never()).write(second);
  }

  @Test
  void fileWrite_whenContinuationEnabled_attemptsLaterItemsAndSuppressesModuleFailure() {
    FileWriteExecutor fileWrites = mock(FileWriteExecutor.class);
    FileWriteItem first = item("first");
    FileWriteItem second = item("second");
    when(fileWrites.write(first))
        .thenReturn(new StepResult.Failure(first.itemKey(), "failed", 1, Duration.ZERO));
    when(fileWrites.write(second))
        .thenReturn(new StepResult.Success(second.itemKey(), Duration.ZERO));
    var module = new FileWriteModule(new ModuleName("files"), List.of(first, second), true);

    boolean failed =
        executor(fileWrites).execute(module, ignored -> {}, phaseExecutors(), runner());

    assertThat(failed).isFalse();
    verify(fileWrites).write(first);
    verify(fileWrites).write(second);
  }

  @Test
  void compiledBinary_whenOnlyItemFails_reportsFailureEvenIfContinuationEnabled() {
    CompiledBinaryInstaller binaries = mock(CompiledBinaryInstaller.class);
    CompiledBinaryModule module =
        new CompiledBinaryModule(
            new ModuleName("tool"),
            "tool",
            new BinaryUrl(URI.create("https://example.test/tool")),
            Optional.of(new Checksum("sha256", "a".repeat(64))),
            Path.of("/usr/local/bin/tool"),
            true);
    when(binaries.install(module))
        .thenReturn(new StepResult.Failure("tool", "checksum mismatch", 1, Duration.ZERO));

    boolean failed =
        executor(mock(FileWriteExecutor.class), binaries)
            .execute(module, ignored -> {}, phaseExecutors(), runner());

    assertThat(failed).isTrue();
  }

  private DirectModuleExecutor executor(FileWriteExecutor fileWrites) {
    return executor(fileWrites, mock(CompiledBinaryInstaller.class));
  }

  private DirectModuleExecutor executor(
      FileWriteExecutor fileWrites, CompiledBinaryInstaller binaries) {
    SkipEvaluator skipEvaluator = SkipEvaluator.alwaysRun();
    var stateRecorder =
        new RunStateRecorder(
            Optional.empty(), "test", skipEvaluator, new PhaseFingerprintCalculator());
    return new DirectModuleExecutor(
        mock(SourceSetupExecutor.class),
        fileWrites,
        mock(FlatpakInstaller.class),
        binaries,
        skipEvaluator,
        stateRecorder,
        new ItemExecution(skipEvaluator, stateRecorder));
  }

  private FileWriteItem item(String name) {
    return new FileWriteItem(
        name,
        Path.of("/tmp/" + name),
        Optional.of(name),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        false);
  }

  private PhaseExecutors phaseExecutors() {
    return PhaseExecutors.forRunner(runner());
  }

  private ShellRunner runner() {
    return (command, environment, timeout) -> new ProcessResult(0, "", "", Duration.ZERO);
  }
}
