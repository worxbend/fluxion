package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.PacmanRepositorySourceSetup;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.util.List;

final class SourceSetupExecutor {

  private final AptRepositoryInstaller aptRepositoryInstaller;
  private final RpmRepositoryInstaller rpmRepositoryInstaller;
  private final PacmanRepositoryInstaller pacmanRepositoryInstaller;
  private final ZypperRepositoryInstaller zypperRepositoryInstaller;
  private final FlatpakRemoteInstaller flatpakRemoteInstaller;

  SourceSetupExecutor(
      AptRepositoryInstaller aptRepositoryInstaller,
      RpmRepositoryInstaller rpmRepositoryInstaller,
      PacmanRepositoryInstaller pacmanRepositoryInstaller,
      ZypperRepositoryInstaller zypperRepositoryInstaller,
      FlatpakRemoteInstaller flatpakRemoteInstaller) {
    this.aptRepositoryInstaller = aptRepositoryInstaller;
    this.rpmRepositoryInstaller = rpmRepositoryInstaller;
    this.pacmanRepositoryInstaller = pacmanRepositoryInstaller;
    this.zypperRepositoryInstaller = zypperRepositoryInstaller;
    this.flatpakRemoteInstaller = flatpakRemoteInstaller;
  }

  StepResult execute(SourceSetup setup) {
    return switch (setup) {
      case AptRepositorySourceSetup apt -> aptRepositoryInstaller.add(aptModule(apt));
      case RpmRepositorySourceSetup rpm -> rpmRepositoryInstaller.add(rpmModule(rpm));
      case ZypperRepositorySourceSetup zypper -> zypperRepositoryInstaller.add(zypper);
      case FlatpakRemoteSourceSetup flatpak -> flatpakRemoteInstaller.add(flatpakModule(flatpak));
      case PacmanRepositorySourceSetup pacman -> pacmanRepositoryInstaller.add(pacmanModule(pacman));
    };
  }

  List<String> commandPreview(SourceSetup setup) {
    return switch (setup) {
      case AptRepositorySourceSetup apt -> aptRepositoryInstaller.addCommand(aptModule(apt));
      case RpmRepositorySourceSetup rpm -> rpmRepositoryInstaller.addCommand(rpmModule(rpm));
      case ZypperRepositorySourceSetup zypper -> zypperRepositoryInstaller.addCommand(zypper);
      case FlatpakRemoteSourceSetup flatpak ->
          flatpakRemoteInstaller.addCommand(flatpakModule(flatpak));
      case PacmanRepositorySourceSetup pacman ->
          pacmanRepositoryInstaller.addCommand(pacmanModule(pacman));
    };
  }

  ModuleItem item(SourceSetup setup) {
    return switch (setup) {
      case AptRepositorySourceSetup apt ->
          new ModuleItem(apt.name(), apt.sourceListPath().toString(), ItemType.APT_REPOSITORY);
      case RpmRepositorySourceSetup rpm ->
          new ModuleItem(rpm.name(), rpm.repoFilePath().toString(), ItemType.RPM_REPOSITORY);
      case ZypperRepositorySourceSetup zypper ->
          new ModuleItem(
              zypper.name(), zypper.repoFilePath().toString(), ItemType.ZYPPER_REPOSITORY);
      case FlatpakRemoteSourceSetup flatpak ->
          new ModuleItem(flatpak.name(), flatpak.remote(), ItemType.FLATPAK_REMOTE);
      case PacmanRepositorySourceSetup pacman ->
          new ModuleItem(pacman.name(), pacman.repositoryName(), ItemType.PACMAN_REPOSITORY);
    };
  }

  private AptRepositoryModule aptModule(AptRepositorySourceSetup setup) {
    return new AptRepositoryModule(
        setup.name(),
        setup.sourceEntry(),
        setup.sourceListPath(),
        setup.signingKeyUrl(),
        setup.keyringPath());
  }

  private RpmRepositoryModule rpmModule(RpmRepositorySourceSetup setup) {
    return new RpmRepositoryModule(
        setup.name(),
        setup.repositoryId(),
        setup.baseUrl(),
        setup.repoFilePath(),
        setup.gpgKeyUrl(),
        setup.enabled(),
        setup.gpgCheck());
  }

  private FlatpakRemoteModule flatpakModule(FlatpakRemoteSourceSetup setup) {
    return new FlatpakRemoteModule(setup.name(), setup.remote(), setup.url(), setup.system());
  }

  private PacmanRepositoryModule pacmanModule(PacmanRepositorySourceSetup setup) {
    return new PacmanRepositoryModule(
        setup.name(),
        setup.repositoryName(),
        setup.server(),
        setup.configPath(),
        setup.sigLevel(),
        setup.include(),
        setup.enabled());
  }
}
