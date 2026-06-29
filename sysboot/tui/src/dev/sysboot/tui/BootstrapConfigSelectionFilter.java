package dev.sysboot.tui;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class BootstrapConfigSelectionFilter {

  BootstrapConfig apply(BootstrapConfig config, BootstrapSelection selection) {
    List<Phase> phases = filteredPhases(config, selection);
    if (phases.isEmpty()) {
      throw new IllegalArgumentException("Select at least one job before running.");
    }
    var builder =
        BootstrapConfig.builder()
            .profileName(config.profileName())
            .target(config.target())
            .policy(config.policy())
            .skippedPlanEntries(config.skippedPlanEntries());
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private List<Phase> filteredPhases(BootstrapConfig config, BootstrapSelection selection) {
    Set<String> selectedPhaseNames =
        config.phases().stream()
            .filter(selection::phaseSelected)
            .map(phase -> phase.name().value())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return config.phases().stream()
        .filter(selection::phaseSelected)
        .map(phase -> filterPhase(phase, selection, selectedPhaseNames))
        .toList();
  }

  private Phase filterPhase(
      Phase phase, BootstrapSelection selection, Set<String> selectedPhaseNames) {
    List<BootstrapModule> modules =
        phase.modules().stream()
            .filter(selection::moduleSelected)
            .map(module -> filterModule(module, selection))
            .flatMap(Optional::stream)
            .toList();
    List<PhaseName> dependencies =
        phase.dependsOn().stream().filter(dep -> selectedPhaseNames.contains(dep.value())).toList();
    return new Phase(
        phase.name(),
        phase.description(),
        modules,
        dependencies,
        phase.restartPolicy(),
        phase.continueOnModuleError());
  }

  private Optional<BootstrapModule> filterModule(
      BootstrapModule module, BootstrapSelection selection) {
    Set<String> entries = selection.selectedEntries(module);
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    return switch (module) {
      case PackageModule packageModule -> filterPackageModule(packageModule, entries);
      case ZypperModule zypperModule -> filterZypperModule(zypperModule, entries);
      case AptRepositoryModule aptRepositoryModule -> Optional.of(aptRepositoryModule);
      case RpmRepositoryModule rpmRepositoryModule -> Optional.of(rpmRepositoryModule);
      case PacmanRepositoryModule pacmanRepositoryModule -> Optional.of(pacmanRepositoryModule);
      case FlatpakModule flatpakModule -> filterFlatpakModule(flatpakModule, entries);
      case FlatpakRemoteModule flatpakRemoteModule -> Optional.of(flatpakRemoteModule);
      case ShellCommandModule shellCommandModule ->
          filterShellCommandModule(shellCommandModule, entries);
      case NerdFontModule nerdFontModule -> filterNerdFontModule(nerdFontModule, entries);
      case CompiledBinaryModule compiledBinaryModule -> Optional.of(compiledBinaryModule);
      case ShellScriptModule shellScriptModule -> Optional.of(shellScriptModule);
      case DotbotModule dotbotModule -> Optional.of(dotbotModule);
      case DefaultShellModule defaultShellModule -> Optional.of(defaultShellModule);
      case OhMyZshModule ohMyZshModule -> Optional.of(ohMyZshModule);
      case ToolchainModule toolchainModule -> Optional.of(toolchainModule);
      case ShellReloadModule shellReloadModule -> Optional.of(shellReloadModule);
      case AssertModule assertModule -> Optional.of(assertModule);
      case ManualModule manualModule -> Optional.of(manualModule);
    };
  }

  private Optional<BootstrapModule> filterPackageModule(PackageModule module, Set<String> entries) {
    var packages = module.packages().stream().filter(p -> entries.contains(p.value())).toList();
    return packages.isEmpty()
        ? Optional.empty()
        : Optional.of(
            new PackageModule(
                module.name(), module.packageManager(), packages, module.continueOnError()));
  }

  private Optional<BootstrapModule> filterZypperModule(ZypperModule module, Set<String> entries) {
    var packages = module.packages().stream().filter(p -> entries.contains(p.value())).toList();
    return packages.isEmpty()
        ? Optional.empty()
        : Optional.of(new ZypperModule(module.name(), packages, module.continueOnError()));
  }

  private Optional<BootstrapModule> filterFlatpakModule(FlatpakModule module, Set<String> entries) {
    var appIds = module.appIds().stream().filter(entries::contains).toList();
    return appIds.isEmpty()
        ? Optional.empty()
        : Optional.of(new FlatpakModule(module.name(), module.remote(), appIds));
  }

  private Optional<BootstrapModule> filterShellCommandModule(
      ShellCommandModule module, Set<String> entries) {
    var items = module.items().stream().filter(item -> entries.contains(item.name())).toList();
    return items.isEmpty()
        ? Optional.empty()
        : Optional.of(
            new ShellCommandModule(
                module.name(),
                items,
                module.shell(),
                module.workingDir(),
                module.continueOnError(),
                module.probeCommand()));
  }

  private Optional<BootstrapModule> filterNerdFontModule(
      NerdFontModule module, Set<String> entries) {
    var families = module.config().families().stream().filter(entries::contains).toList();
    if (families.isEmpty()) {
      return Optional.empty();
    }
    var config =
        new NerdFontConfig(
            module.config().release(),
            module.config().destination(),
            module.config().refreshFontCache(),
            families);
    return Optional.of(
        new NerdFontModule(
            module.name(),
            module.installerVersion(),
            module.nerdfontBinary(),
            config,
            module.probeCommand()));
  }
}
