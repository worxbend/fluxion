package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    List<ModuleItem> targets = collectProbeTargets(modules);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (ModuleItem target : targets) {
        futures.add(
            executor.submit(
                () -> {
                  InstallationStatus status = probeRegistry.probe(target);
                  results.put(target.key(), status);
                  progressCallback.accept(target.key());
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

  private List<ModuleItem> collectProbeTargets(List<BootstrapModule> modules) {
    List<ModuleItem> targets = new ArrayList<>();
    for (BootstrapModule module : modules) {
      switch (module) {
        case PackageModule pm ->
            pm.packages()
                .forEach(
                    pkg ->
                        targets.add(
                            ModuleItem.packageItem(pm.name(), pkg.value(), pm.packageManager())));
        case ZypperModule zm ->
            zm.packages()
                .forEach(
                    pkg ->
                        targets.add(
                            ModuleItem.packageItem(
                                zm.name(), pkg.value(), PackageManagerKind.ZYPPER)));
        case AptRepositoryModule arm ->
            targets.add(
                new ModuleItem(
                    arm.name(), arm.sourceListPath().toString(), ItemType.APT_REPOSITORY));
        case RpmRepositoryModule rrm ->
            targets.add(
                new ModuleItem(rrm.name(), rrm.repoFilePath().toString(), ItemType.RPM_REPOSITORY));
        case PacmanRepositoryModule prm ->
            targets.add(
                new ModuleItem(prm.name(), prm.repositoryName(), ItemType.PACMAN_REPOSITORY));
        case FlatpakModule fm ->
            fm.appIds()
                .forEach(appId -> targets.add(new ModuleItem(fm.name(), appId, ItemType.FLATPAK)));
        case FlatpakRemoteModule frm ->
            targets.add(new ModuleItem(frm.name(), frm.remote(), ItemType.FLATPAK_REMOTE));
        case ShellScriptModule sm ->
            sm.items()
                .forEach(
                    item ->
                        targets.add(
                            new ModuleItem(
                                sm.name(), item.name(), item.key(), ItemType.SHELL_SCRIPT,
                                Optional.empty())));
        case CompiledBinaryModule bm ->
            targets.add(
                new ModuleItem(bm.name(), bm.installPath().toString(), ItemType.COMPILED_BINARY));
        case DotbotModule dm ->
            targets.add(new ModuleItem(dm.name(), dm.config().toString(), ItemType.DOTBOT));
        case DefaultShellModule dsm ->
            targets.add(
                new ModuleItem(dsm.name(), dsm.shellPath().toString(), ItemType.DEFAULT_SHELL));
        case OhMyZshModule omz ->
            targets.add(
                new ModuleItem(omz.name(), omz.installDir().toString(), ItemType.OH_MY_ZSH));
        case ToolchainModule tm ->
            targets.add(
                new ModuleItem(tm.name(), tm.kind().name().toLowerCase(), ItemType.TOOLCHAIN));
        case NerdFontModule nfm ->
            targets.add(
                new ModuleItem(
                    nfm.name(),
                    nfm.config().families().isEmpty()
                        ? nfm.name().value()
                        : nfm.config().families().get(0),
                    ItemType.NERD_FONT));
        case ShellReloadModule srm ->
            targets.add(
                new ModuleItem(srm.name(), srm.shell().binaryName(), ItemType.SHELL_RELOAD));
        case ShellCommandModule sc ->
            sc.items()
                .forEach(
                    item ->
                        targets.add(new ModuleItem(sc.name(), item.name(), ItemType.SHELL_COMMAND)));
        case AssertModule am ->
            targets.add(new ModuleItem(am.name(), am.name().value(), ItemType.ASSERT));
        case ManualModule mm ->
            targets.add(new ModuleItem(mm.name(), mm.name().value(), ItemType.MANUAL));
        case InterruptModule im ->
            targets.add(new ModuleItem(im.name(), im.name().value(), ItemType.INTERRUPT));
      }
    }
    return List.copyOf(targets);
  }
}
