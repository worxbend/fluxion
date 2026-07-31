package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpgKeyDispatchTest {

  private static final String FIRST = "A".repeat(40);
  private static final String SECOND = "B".repeat(40);

  @TempDir Path tempDirectory;

  @Test
  void continueOnError_keepsFailureAndRecordsOnlySuccessfulFingerprintIdentity() throws Exception {
    Path firstSource = Files.writeString(tempDirectory.resolve("first.asc"), "first");
    Path secondSource = Files.writeString(tempDirectory.resolve("second.asc"), "second");
    var module =
        new GpgKeyModule(
            new ModuleName("repository-keys"),
            List.of(
                new GpgKeyModule.GpgKey(firstSource.toUri().toString(), Optional.empty(), FIRST),
                new GpgKeyModule.GpgKey(secondSource.toUri().toString(), Optional.empty(), SECOND)),
            true);
    var runner = new ScriptedRunner();
    var repository = new JsonStateRepository(tempDirectory.resolve("state"), new ObjectMapper());
    var skipEvaluator = SkipEvaluator.alwaysRun();
    var stateRecorder =
        new RunStateRecorder(
            Optional.of(repository),
            "test-profile",
            skipEvaluator,
            new PhaseFingerprintCalculator());
    var itemExecution = new ItemExecution(skipEvaluator, stateRecorder);
    var directExecutor =
        new DirectModuleExecutor(
            mock(SourceSetupExecutor.class),
            mock(FileWriteExecutor.class),
            mock(FlatpakInstaller.class),
            mock(CompiledBinaryInstaller.class),
            skipEvaluator,
            stateRecorder,
            itemExecution);

    boolean failed =
        directExecutor.execute(module, event -> {}, PhaseExecutors.forRunner(runner), runner);

    assertThat(failed).isTrue();
    assertThat(repository.load("test-profile").orElseThrow().entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.moduleName()).isEqualTo("repository-keys");
              assertThat(entry.itemKey()).isEqualTo("fingerprint:" + SECOND);
            });
  }

  private static ProcessResult inspection(String fingerprint) {
    return new ProcessResult(
        0, "pub:-:4096:1:KEYID:0:0::::::\nfpr:::::::::" + fingerprint + ":\n", "", Duration.ZERO);
  }

  private static final class ScriptedRunner implements ShellRunner {

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      return command.contains("--show-keys")
          ? inspection(SECOND)
          : new ProcessResult(0, "", "", Duration.ZERO);
    }
  }
}
