package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ParallelProbeRunner {

  private static final int GLOBAL_TIMEOUT_SECONDS = 60;

  private final InstalledProbeRegistry probeRegistry;

  public ParallelProbeRunner(InstalledProbeRegistry probeRegistry) {
    this.probeRegistry = probeRegistry;
  }

  public Map<String, InstallationStatus> probeAll(
      List<BootstrapModule> modules, Consumer<String> progressCallback) {

    var results = new ConcurrentHashMap<String, InstallationStatus>();
    List<ProbeTarget> targets = collectProbeTargets(modules);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (ProbeTarget target : targets) {
        futures.add(
            executor.submit(
                () -> {
                  InstallationStatus status =
                      probeRegistry.probe(target.itemKey(), target.itemType());
                  results.put(target.itemKey(), status);
                  progressCallback.accept(target.itemKey());
                }));
      }

      for (Future<?> future : futures) {
        try {
          future.get(GLOBAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          // probe timed out — result absent, treated as Unknown by caller
        } catch (java.util.concurrent.ExecutionException e) {
          // probe threw — result absent
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    return Collections.unmodifiableMap(results);
  }

  private List<ProbeTarget> collectProbeTargets(List<BootstrapModule> modules) {
    List<ProbeTarget> targets = new ArrayList<>();
    for (BootstrapModule module : modules) {
      switch (module) {
        case PackageModule pm ->
            pm.packages()
                .forEach(
                    pkg ->
                        targets.add(
                            new ProbeTarget(pkg.value(), ItemType.PACKAGE, pm.name().value())));
        case ZypperModule zm ->
            zm.packages()
                .forEach(
                    pkg ->
                        targets.add(
                            new ProbeTarget(pkg.value(), ItemType.PACKAGE, zm.name().value())));
        case FlatpakModule fm ->
            fm.appIds()
                .forEach(
                    appId ->
                        targets.add(new ProbeTarget(appId, ItemType.FLATPAK, fm.name().value())));
        case ShellScriptModule sm ->
            targets.add(
                new ProbeTarget(sm.script().toString(), ItemType.SHELL_SCRIPT, sm.name().value()));
        case CompiledBinaryModule bm ->
            targets.add(
                new ProbeTarget(
                    bm.installPath().toString(), ItemType.COMPILED_BINARY, bm.name().value()));
        case DotbotModule dm ->
            targets.add(
                new ProbeTarget(dm.config().toString(), ItemType.DOTBOT, dm.name().value()));
        case DefaultShellModule dsm ->
            targets.add(
                new ProbeTarget(
                    dsm.shellPath().toString(), ItemType.DEFAULT_SHELL, dsm.name().value()));
        case OhMyZshModule omz ->
            targets.add(
                new ProbeTarget(
                    omz.installDir().toString(), ItemType.OH_MY_ZSH, omz.name().value()));
        case ToolchainModule tm ->
            targets.add(
                new ProbeTarget(
                    tm.kind().name().toLowerCase(), ItemType.TOOLCHAIN, tm.name().value()));
        case NerdFontModule nfm ->
            targets.add(
                new ProbeTarget(
                    nfm.config().families().isEmpty()
                        ? nfm.name().value()
                        : nfm.config().families().get(0),
                    ItemType.NERD_FONT,
                    nfm.name().value()));
        case ShellReloadModule srm ->
            targets.add(
                new ProbeTarget(
                    srm.shell().binaryName(), ItemType.SHELL_RELOAD, srm.name().value()));
        case ShellCommandModule sc ->
            targets.add(
                new ProbeTarget(sc.name().value(), ItemType.SHELL_COMMAND, sc.name().value()));
      }
    }
    return List.copyOf(targets);
  }
}
