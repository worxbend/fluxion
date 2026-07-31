package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ModuleItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ParallelProbeRunner {

  private static final int DEFAULT_MAX_CONCURRENCY = 16;
  private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(60);

  private final InstalledProbeRegistry probeRegistry;
  private final int maxConcurrency;
  private final Duration deadline;

  public ParallelProbeRunner(InstalledProbeRegistry probeRegistry) {
    this(probeRegistry, DEFAULT_MAX_CONCURRENCY, DEFAULT_DEADLINE);
  }

  public ParallelProbeRunner(
      InstalledProbeRegistry probeRegistry, int maxConcurrency, Duration deadline) {
    this.probeRegistry = Objects.requireNonNull(probeRegistry);
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("Probe concurrency must be positive");
    }
    this.maxConcurrency = maxConcurrency;
    this.deadline = Objects.requireNonNull(deadline);
    if (deadline.isNegative() || deadline.isZero()) {
      throw new IllegalArgumentException("Probe deadline must be positive");
    }
  }

  public Map<String, InstallationStatus> probeAll(
      List<BootstrapModule> modules, Consumer<String> progressCallback) {
    Objects.requireNonNull(modules);
    return probeItems(
        modules.stream().flatMap(module -> ModuleItemCatalog.items(module).stream()).toList(),
        progressCallback);
  }

  public Map<String, InstallationStatus> probeAll(
      ExecutionPlan plan, Consumer<String> progressCallback) {
    Objects.requireNonNull(plan);
    var items = new ArrayList<ModuleItem>();
    plan.sourceSetups().stream()
        .flatMap(module -> module.items().stream())
        .map(ExecutionPlan.Item::item)
        .forEach(items::add);
    plan.phases().stream()
        .flatMap(phase -> phase.modules().stream())
        .flatMap(module -> module.items().stream())
        .map(ExecutionPlan.Item::item)
        .forEach(items::add);
    return probeItems(List.copyOf(items), progressCallback);
  }

  private Map<String, InstallationStatus> probeItems(
      List<ModuleItem> targets, Consumer<String> progressCallback) {
    Objects.requireNonNull(progressCallback);
    var results = new ConcurrentHashMap<String, InstallationStatus>();
    ExecutorService executor =
        Executors.newFixedThreadPool(
            Math.min(maxConcurrency, Math.max(1, targets.size())),
            Thread.ofVirtual().name("sysboot-probe-", 0).factory());
    try {
      var tasks =
          targets.stream()
              .<java.util.concurrent.Callable<Void>>map(
                  target ->
                      () -> {
                        probe(target, progressCallback, results);
                        return null;
                      })
              .toList();
      var futures = executor.invokeAll(tasks, deadline.toNanos(), TimeUnit.NANOSECONDS);
      for (int index = 0; index < futures.size(); index++) {
        ModuleItem target = targets.get(index);
        if (!results.containsKey(target.qualifiedKey())) {
          String reason =
              futures.get(index).isCancelled()
                  ? "Probe deadline exceeded"
                  : "Probe failed without a result";
          results.putIfAbsent(
              target.qualifiedKey(), new InstallationStatus.Unknown(target.key(), reason));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      addUnknownResults(targets, results, "Probe interrupted");
    } finally {
      executor.shutdownNow();
    }
    return Map.copyOf(results);
  }

  private void addUnknownResults(
      List<ModuleItem> targets, Map<String, InstallationStatus> results, String reason) {
    targets.forEach(
        target ->
            results.putIfAbsent(
                target.qualifiedKey(), new InstallationStatus.Unknown(target.key(), reason)));
  }

  private void probe(
      ModuleItem target,
      Consumer<String> progressCallback,
      Map<String, InstallationStatus> results) {
    InstallationStatus status = probeRegistry.probe(target);
    if (Thread.currentThread().isInterrupted()) {
      return;
    }
    results.put(target.qualifiedKey(), status);
    progressCallback.accept(target.qualifiedKey());
  }
}
