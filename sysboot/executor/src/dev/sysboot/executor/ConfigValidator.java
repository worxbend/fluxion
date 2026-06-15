package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ZypperModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConfigValidator {

  private final PhaseExecutionPlanner planner;

  public ConfigValidator() {
    this(new PhaseExecutionPlanner());
  }

  ConfigValidator(PhaseExecutionPlanner planner) {
    this.planner = planner;
  }

  public ValidationReport validate(BootstrapConfig config) {
    var issues = new ArrayList<ValidationIssue>();
    validatePhaseGraph(config, issues);
    validateModules(config, issues);
    return new ValidationReport(
        config.profileName().value(), config.phases().size(), config.modules().size(), issues);
  }

  private void validatePhaseGraph(BootstrapConfig config, List<ValidationIssue> issues) {
    try {
      planner.plan(config.phases());
    } catch (CyclicDependencyException e) {
      addError(issues, "jobs", "Cycle in job dependency graph: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      addError(issues, "jobs", "Invalid job dependency: " + e.getMessage());
    }
  }

  private void validateModules(BootstrapConfig config, List<ValidationIssue> issues) {
    for (int phaseIndex = 0; phaseIndex < config.phases().size(); phaseIndex++) {
      Phase phase = config.phases().get(phaseIndex);
      validatePhaseModules(config, phase, phaseIndex, issues);
    }
  }

  private void validatePhaseModules(
      BootstrapConfig config, Phase phase, int phaseIndex, List<ValidationIssue> issues) {
    for (int moduleIndex = 0; moduleIndex < phase.modules().size(); moduleIndex++) {
      BootstrapModule module = phase.modules().get(moduleIndex);
      String path = "jobs[%d].steps[%d]".formatted(phaseIndex, moduleIndex);
      validateModule(config, module, path, issues);
    }
  }

  private void validateModule(
      BootstrapConfig config, BootstrapModule module, String path, List<ValidationIssue> issues) {
    switch (module) {
      case PackageModule packageModule ->
          validatePackageModule(config, packageModule, path, issues);
      case ZypperModule zypperModule ->
          validatePackageModule(config, zypperModule.asPackageModule(), path, issues);
      case AptRepositoryModule aptRepositoryModule ->
          validateAptRepository(config, aptRepositoryModule, path, issues);
      case RpmRepositoryModule rpmRepositoryModule ->
          validateRpmRepository(config, rpmRepositoryModule, path, issues);
      case FlatpakRemoteModule flatpakRemoteModule ->
          validateFlatpakRemote(flatpakRemoteModule, path, issues);
      case CompiledBinaryModule binaryModule -> validateCompiledBinary(binaryModule, path, issues);
      default -> {}
    }
  }

  private void validateFlatpakRemote(
      FlatpakRemoteModule module, String path, List<ValidationIssue> issues) {
    if (!"https".equalsIgnoreCase(module.url().getScheme())) {
      addWarning(
          issues,
          path + ".url",
          "Flatpak remote '%s' should use an HTTPS repository URL".formatted(module.remote()));
    }
  }

  private void validateAptRepository(
      BootstrapConfig config,
      AptRepositoryModule module,
      String path,
      List<ValidationIssue> issues) {
    if (!(config.target() instanceof OsTarget.DebianTarget)) {
      addError(issues, path + ".type", "APT repositories are only valid for debian targets");
    }
    module
        .signingKeyUrl()
        .filter(url -> !"https".equalsIgnoreCase(url.getScheme()))
        .ifPresent(
            url -> addWarning(issues, path + ".signingKeyUrl", "Signing key URL should use HTTPS"));
    if (module.signingKeyUrl().isEmpty()) {
      addWarning(
          issues,
          path + ".signingKeyUrl",
          "APT repository '%s' has no signing key URL".formatted(module.name().value()));
    }
  }

  private void validateRpmRepository(
      BootstrapConfig config,
      RpmRepositoryModule module,
      String path,
      List<ValidationIssue> issues) {
    if (!(config.target() instanceof OsTarget.FedoraTarget)) {
      addError(issues, path + ".type", "RPM repositories are only valid for fedora targets");
    }
    if (!"https".equalsIgnoreCase(module.baseUrl().getScheme())) {
      addWarning(issues, path + ".baseUrl", "RPM repository base URL should use HTTPS");
    }
    module
        .gpgKeyUrl()
        .filter(url -> !"https".equalsIgnoreCase(url.getScheme()))
        .ifPresent(url -> addWarning(issues, path + ".gpgKeyUrl", "GPG key URL should use HTTPS"));
    if (module.gpgCheck() && module.gpgKeyUrl().isEmpty()) {
      addWarning(
          issues,
          path + ".gpgKeyUrl",
          "RPM repository '%s' has gpgCheck enabled but no GPG key URL"
              .formatted(module.name().value()));
    }
  }

  private void validatePackageModule(
      BootstrapConfig config, PackageModule module, String path, List<ValidationIssue> issues) {
    if (!managerAllowed(config.target(), module.packageManager())) {
      addError(
          issues,
          path + ".packageManager",
          "Package manager %s is not valid for target %s"
              .formatted(
                  module.packageManager().name().toLowerCase(), targetName(config.target())));
    }
    validateDuplicatePackages(module, path, issues);
  }

  private void validateDuplicatePackages(
      PackageModule module, String path, List<ValidationIssue> issues) {
    Set<String> seen = new HashSet<>();
    for (var packageName : module.packages()) {
      if (!seen.add(packageName.value())) {
        addWarning(
            issues,
            path + ".packages",
            "Duplicate package '%s' in module '%s'"
                .formatted(packageName.value(), module.name().value()));
      }
    }
  }

  private void validateCompiledBinary(
      CompiledBinaryModule module, String path, List<ValidationIssue> issues) {
    if (!CompiledBinaryArtifactFormat.isSupported(module.url().value())) {
      addError(
          issues,
          path + ".url",
          "Compiled binary '%s' uses an unsupported artifact format; supported formats are %s"
              .formatted(module.name().value(), CompiledBinaryArtifactFormat.supportedFormats()));
    }
    if (module.checksum().isPresent() && module.checksumUrl().isPresent()) {
      addError(
          issues,
          path + ".checksum",
          "Compiled binary '%s' must declare either checksum or checksumUrl, not both"
              .formatted(module.name().value()));
    }
    module
        .checksum()
        .filter(checksum -> !"SHA-256".equals(checksum.algorithm()))
        .ifPresent(
            checksum ->
                addError(
                    issues,
                    path + ".checksum.algorithm",
                    "Compiled binary '%s' uses unsupported checksum algorithm '%s'"
                        .formatted(module.name().value(), checksum.algorithm())));
    if (module.checksum().isEmpty() && module.checksumUrl().isEmpty()) {
      addWarning(
          issues,
          path + ".checksum",
          "Compiled binary '%s' has no checksum".formatted(module.name().value()));
    }
  }

  private boolean managerAllowed(OsTarget target, PackageManagerKind manager) {
    return switch (target) {
      case OsTarget.FedoraTarget ignored -> manager == PackageManagerKind.DNF;
      case OsTarget.ArchTarget ignored ->
          Set.of(PackageManagerKind.PACMAN, PackageManagerKind.PARU, PackageManagerKind.YAY)
              .contains(manager);
      case OsTarget.OpenSuseTarget ignored -> manager == PackageManagerKind.ZYPPER;
      case OsTarget.DebianTarget ignored -> manager == PackageManagerKind.APT;
    };
  }

  private String targetName(OsTarget target) {
    return switch (target) {
      case OsTarget.FedoraTarget ignored -> "fedora";
      case OsTarget.ArchTarget ignored -> "arch";
      case OsTarget.OpenSuseTarget ignored -> "opensuse";
      case OsTarget.DebianTarget ignored -> "debian";
    };
  }

  private void addError(List<ValidationIssue> issues, String path, String message) {
    issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, path, message));
  }

  private void addWarning(List<ValidationIssue> issues, String path, String message) {
    issues.add(new ValidationIssue(ValidationIssue.Severity.WARNING, path, message));
  }
}
