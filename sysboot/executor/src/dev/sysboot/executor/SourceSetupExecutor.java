package dev.sysboot.executor;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.PacmanRepositorySourceSetup;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class SourceSetupExecutor {

  private final AptRepositoryInstaller aptInstaller;
  private final RpmRepositoryInstaller rpmInstaller;
  private final PacmanRepositoryInstaller pacmanInstaller;
  private final ZypperRepositoryInstaller zypperInstaller;
  private final FlatpakRemoteInstaller flatpakInstaller;
  private final SourceArtifactDownloadClient artifactDownloader;

  SourceSetupExecutor(
      AptRepositoryInstaller aptInstaller,
      RpmRepositoryInstaller rpmInstaller,
      PacmanRepositoryInstaller pacmanInstaller,
      ZypperRepositoryInstaller zypperInstaller,
      FlatpakRemoteInstaller flatpakInstaller) {
    this(
        aptInstaller,
        rpmInstaller,
        pacmanInstaller,
        zypperInstaller,
        flatpakInstaller,
        new VerifiedSourceArtifactDownloader());
  }

  SourceSetupExecutor(
      AptRepositoryInstaller aptInstaller,
      RpmRepositoryInstaller rpmInstaller,
      PacmanRepositoryInstaller pacmanInstaller,
      ZypperRepositoryInstaller zypperInstaller,
      FlatpakRemoteInstaller flatpakInstaller,
      SourceArtifactDownloadClient artifactDownloader) {
    this.aptInstaller = aptInstaller;
    this.rpmInstaller = rpmInstaller;
    this.pacmanInstaller = pacmanInstaller;
    this.zypperInstaller = zypperInstaller;
    this.flatpakInstaller = flatpakInstaller;
    this.artifactDownloader = artifactDownloader;
  }

  StepResult execute(SourceSetup setup) {
    return switch (setup) {
      case AptRepositorySourceSetup apt -> executeApt(apt);
      case RpmRepositorySourceSetup rpm -> executeRpm(rpm);
      case ZypperRepositorySourceSetup zypper -> executeZypper(zypper);
      case FlatpakRemoteSourceSetup flatpak -> executeFlatpak(flatpak);
      case PacmanRepositorySourceSetup pacman -> pacmanInstaller.add(pacmanModule(pacman));
    };
  }

  List<String> commandPreview(SourceSetup setup) {
    return List.of(
        "sysboot-source-setup",
        setup.packageManager().name().toLowerCase(),
        setup.name().value(),
        artifactSha256(setup)
            .map(value -> "verify-sha256=" + value.value())
            .orElse("no-remote-artifact"));
  }

  ModuleItem item(SourceSetup setup) {
    return ModuleItemCatalog.sourceItem(setup);
  }

  private StepResult executeApt(AptRepositorySourceSetup setup) {
    if (setup.signingKeyUrl().isEmpty()) {
      return setup.artifactSha256().isPresent()
          ? missingTrust(setup.sourceListPath().toString())
          : aptInstaller.addTrusted(setup, Optional.empty());
    }
    return withArtifact(
        setup.sourceListPath().toString(),
        setup.signingKeyUrl().orElseThrow(),
        setup.artifactSha256(),
        artifact -> aptInstaller.addTrusted(setup, Optional.of(artifact)));
  }

  private StepResult executeRpm(RpmRepositorySourceSetup setup) {
    if (setup.gpgKeyUrl().isEmpty()) {
      return setup.artifactSha256().isPresent() || setup.gpgCheck()
          ? missingTrust(setup.repoFilePath().toString())
          : rpmInstaller.addTrusted(setup, Optional.empty());
    }
    return withArtifact(
        setup.repoFilePath().toString(),
        setup.gpgKeyUrl().orElseThrow(),
        setup.artifactSha256(),
        artifact -> rpmInstaller.addTrusted(setup, Optional.of(artifact)));
  }

  private StepResult executeZypper(ZypperRepositorySourceSetup setup) {
    if (setup.gpgKeyUrl().isEmpty()) {
      return setup.artifactSha256().isPresent() || setup.gpgCheck()
          ? missingTrust(setup.repoFilePath().toString())
          : zypperInstaller.addTrusted(setup, Optional.empty());
    }
    return withArtifact(
        setup.repoFilePath().toString(),
        setup.gpgKeyUrl().orElseThrow(),
        setup.artifactSha256(),
        artifact -> zypperInstaller.addTrusted(setup, Optional.of(artifact)));
  }

  private StepResult executeFlatpak(FlatpakRemoteSourceSetup setup) {
    return withArtifact(
        setup.remote(),
        setup.url(),
        setup.artifactSha256(),
        artifact -> flatpakInstaller.addTrusted(setup, artifact));
  }

  private StepResult withArtifact(
      String item, URI url, Optional<Sha256Digest> checksum, Function<Path, StepResult> action) {
    if (checksum.isEmpty()) {
      return missingTrust(item);
    }
    Path artifact = null;
    try {
      artifact = artifactDownloader.download(url, checksum.orElseThrow());
      return action.apply(artifact);
    } catch (IOException e) {
      return new StepResult.Failure(
          item, "Remote source artifact download or SHA-256 verification failed", 1, Duration.ZERO);
    } finally {
      deleteArtifact(artifact);
    }
  }

  private StepResult missingTrust(String item) {
    return new StepResult.Failure(
        item, "Remote source artifact requires a SHA-256 checksum", 1, Duration.ZERO);
  }

  private void deleteArtifact(Path artifact) {
    if (artifact == null) {
      return;
    }
    try {
      Files.deleteIfExists(artifact);
    } catch (IOException ignored) {
      // The install result remains authoritative.
    }
  }

  private Optional<Sha256Digest> artifactSha256(SourceSetup setup) {
    return switch (setup) {
      case AptRepositorySourceSetup apt -> apt.artifactSha256();
      case RpmRepositorySourceSetup rpm -> rpm.artifactSha256();
      case ZypperRepositorySourceSetup zypper -> zypper.artifactSha256();
      case FlatpakRemoteSourceSetup flatpak -> flatpak.artifactSha256();
      case PacmanRepositorySourceSetup ignored -> Optional.empty();
    };
  }

  private PacmanRepositoryModule pacmanModule(PacmanRepositorySourceSetup setup) {
    ModuleName name = setup.name();
    return new PacmanRepositoryModule(
        name,
        setup.repositoryName(),
        setup.server(),
        setup.configPath(),
        setup.sigLevel(),
        setup.include(),
        setup.enabled());
  }
}
