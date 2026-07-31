package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.ZypperModule;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class RelevantSourceSetups {

  private RelevantSourceSetups() {}

  static List<SourceSetup> select(List<SourceSetup> setups, List<Phase> phases) {
    Set<PackageManagerKind> managers = requiredManagers(phases);
    return setups.stream().filter(setup -> managers.contains(setup.packageManager())).toList();
  }

  private static Set<PackageManagerKind> requiredManagers(List<Phase> phases) {
    var managers = EnumSet.noneOf(PackageManagerKind.class);
    phases.stream()
        .flatMap(phase -> phase.modules().stream())
        .map(RelevantSourceSetups::manager)
        .flatMap(Optional::stream)
        .map(RelevantSourceSetups::sourceManager)
        .forEach(managers::add);
    return managers;
  }

  private static Optional<PackageManagerKind> manager(BootstrapModule module) {
    if (module instanceof PackageModule packages) {
      return Optional.of(packages.packageManager());
    }
    if (module instanceof SystemUpdateModule update) {
      return Optional.of(update.packageManager());
    }
    if (module instanceof ZypperModule) {
      return Optional.of(PackageManagerKind.ZYPPER);
    }
    if (module instanceof FlatpakModule) {
      return Optional.of(PackageManagerKind.FLATPAK);
    }
    return Optional.empty();
  }

  private static PackageManagerKind sourceManager(PackageManagerKind manager) {
    return switch (manager) {
      case PARU, YAY -> PackageManagerKind.PACMAN;
      default -> manager;
    };
  }
}
