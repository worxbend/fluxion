package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.MetadataDocument;
import dev.sysboot.config.yaml.contract.TargetDocument;
import dev.sysboot.config.yaml.contract.TargetOsDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class WorkstationProfileConfigMapper {

  private final WorkstationProfileValidator validator;

  WorkstationProfileConfigMapper() {
    this.validator = new WorkstationProfileValidator();
  }

  BootstrapConfig map(WorkstationProfileDocument document, Path manifestPath) {
    validator.validate(document, manifestPath);
    MetadataDocument metadata = requireField(document.metadata().orElse(null), "metadata");
    TargetDocument target =
        requireField(document.spec().orElse(null), "spec").target().orElse(null);
    return BootstrapConfig.builder()
        .profileName(new ProfileName(requireField(metadata.name().orElse(null), "metadata.name")))
        .target(mapTarget(requireField(target, "spec.target")))
        .addPhase(manifestPhase())
        .build();
  }

  private Phase manifestPhase() {
    return new Phase(
        new PhaseName("manifest-plan"),
        "WorkstationProfile plan",
        List.of(),
        List.of(),
        new RestartPolicy.None());
  }

  private OsTarget mapTarget(TargetDocument target) {
    return mapOs(requireField(target.os().orElse(null), "spec.target.os"));
  }

  private OsTarget mapOs(TargetOsDocument os) {
    String distribution =
        requireField(os.distribution().orElse(null), "spec.target.os.distribution")
            .strip()
            .toLowerCase(Locale.ROOT);
    return switch (distribution) {
      case "fedora" -> new OsTarget.FedoraTarget(release(os));
      case "arch" -> new OsTarget.ArchTarget();
      case "opensuse" -> new OsTarget.OpenSuseTarget(release(os));
      case "debian", "ubuntu" -> new OsTarget.DebianTarget(debianRelease(os));
      default ->
          throw new IllegalArgumentException("Unsupported target OS distribution: " + distribution);
    };
  }

  private String release(TargetOsDocument os) {
    return os.release().or(() -> os.version()).orElse("");
  }

  private String debianRelease(TargetOsDocument os) {
    return os.codename().or(() -> os.release()).or(() -> os.version()).orElse("");
  }

  private <T> T requireField(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException("Required field '" + fieldName + "' is missing");
    }
    if (value instanceof String s && s.isBlank()) {
      throw new IllegalArgumentException("Required field '" + fieldName + "' must not be blank");
    }
    return value;
  }
}
