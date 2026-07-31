package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStateRecorderTest {

  @TempDir Path tempDir;

  @Test
  void recordBinarySuccess_whenUrlContainsQueryAndFragment_persistsSanitizedSource()
      throws Exception {
    var repository = new JsonStateRepository(tempDir, new ObjectMapper());
    var recorder =
        new RunStateRecorder(
            Optional.of(repository),
            "test-profile",
            SkipEvaluator.alwaysRun(),
            new PhaseFingerprintCalculator());
    var module = module("https://example.test/rg?token=do-not-store#private-fragment");

    recorder.recordBinarySuccess(
        module, "/usr/local/bin/rg", new StepResult.Success("rg", Duration.ZERO));

    var entry = repository.load("test-profile").orElseThrow().entries().getFirst();
    assertThat(entry.sourceUrl()).contains("https://example.test/rg");
    assertThat(Files.readString(repository.path("test-profile")))
        .doesNotContain("do-not-store", "private-fragment");
    assertThat(module.url().value().getRawQuery()).isEqualTo("token=do-not-store");
  }

  @Test
  void recordSuccess_persistsInjectedLastRunTimestampAndCurrentVersion() {
    var repository = new JsonStateRepository(tempDir, new ObjectMapper());
    Instant timestamp = Instant.parse("2026-07-31T04:05:06Z");
    var recorder =
        new RunStateRecorder(
            Optional.of(repository),
            "test-profile",
            SkipEvaluator.alwaysRun(),
            new PhaseFingerprintCalculator(),
            Clock.fixed(timestamp, ZoneOffset.UTC));

    recorder.recordSuccess(
        new ModuleName("tools"),
        "git",
        dev.sysboot.core.ItemType.PACKAGE,
        new StepResult.Success("git", Duration.ZERO));

    var state = repository.load("test-profile").orElseThrow();
    assertThat(state.lastRunAt()).isEqualTo(timestamp);
    assertThat(state.sysbootVersion()).isEqualTo(dev.sysboot.core.FluxionVersion.current());
  }

  @Test
  void recordPhase_concurrentRecordersDoNotLoseTransitions() throws Exception {
    Path stateRoot = tempDir.resolve("concurrent");
    var firstRepository = new JsonStateRepository(stateRoot, new ObjectMapper());
    var secondRepository = new JsonStateRepository(stateRoot, new ObjectMapper());
    var first = recorder(firstRepository);
    var second = recorder(secondRepository);
    int phaseCount = 32;
    List<Callable<Void>> writes =
        java.util.stream.IntStream.range(0, phaseCount)
            .mapToObj(
                index ->
                    (Callable<Void>)
                        () -> {
                          (index % 2 == 0 ? first : second)
                              .recordPhase(
                                  new PhaseName("phase-" + index),
                                  PhaseStatus.COMPLETED,
                                  "fingerprint-" + index,
                                  Optional.empty());
                          return null;
                        })
            .toList();

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var future : executor.invokeAll(writes)) {
        future.get();
      }
    }

    assertThat(firstRepository.load("test-profile").orElseThrow().phaseEntries())
        .hasSize(phaseCount);
  }

  private RunStateRecorder recorder(JsonStateRepository repository) {
    return new RunStateRecorder(
        Optional.of(repository),
        "test-profile",
        SkipEvaluator.alwaysRun(),
        new PhaseFingerprintCalculator());
  }

  private CompiledBinaryModule module(String url) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create(url)),
        Optional.of(new Checksum("sha256", "a".repeat(64))),
        Path.of("/usr/local/bin/rg"),
        false);
  }
}
