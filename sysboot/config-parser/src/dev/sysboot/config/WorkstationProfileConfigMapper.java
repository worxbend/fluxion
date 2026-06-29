package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.MetadataDocument;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.TargetDocument;
import dev.sysboot.config.yaml.contract.TargetOsDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.HostFactsProvider;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.SkippedPlanEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class WorkstationProfileConfigMapper {

  private final WorkstationProfileValidator validator;
  private final WorkstationProfileWhenEvaluator whenEvaluator;
  private final WorkstationProfileSourceMapper sourceMapper;

  WorkstationProfileConfigMapper(HostFactsProvider hostFactsProvider) {
    this.validator = new WorkstationProfileValidator();
    this.whenEvaluator = new WorkstationProfileWhenEvaluator(hostFactsProvider);
    this.sourceMapper = new WorkstationProfileSourceMapper();
  }

  BootstrapConfig map(WorkstationProfileDocument document, Path manifestPath) {
    validator.validate(document, manifestPath);
    MetadataDocument metadata = requireField(document.metadata().orElse(null), "metadata");
    var spec = requireField(document.spec().orElse(null), "spec");
    TargetDocument target = spec.target().orElse(null);
    BootstrapPolicy policy = mapPolicy(spec.policy());
    WorkstationProfileWhenEvaluator.PlanSelection selection = whenEvaluator.select(spec.plan());
    WorkstationProfileSourceMapper.SourceMapping sourceMapping =
        sourceMapper.map(spec.sources(), selection.selected());
    return BootstrapConfig.builder()
        .profileName(new ProfileName(requireField(metadata.name().orElse(null), "metadata.name")))
        .target(mapTarget(requireField(target, "spec.target")))
        .policy(policy)
        .skippedPlanEntries(skippedEntries(selection.skipped(), sourceMapping.skippedEntries()))
        .sourceSetups(sourceMapping.sourceSetups())
        .addPhase(manifestPhase(selection.selected(), policy))
        .build();
  }

  private List<SkippedPlanEntry> skippedEntries(
      List<WorkstationProfileWhenEvaluator.SkippedPlanEntry> planEntries,
      List<SkippedPlanEntry> sourceEntries) {
    var entries = new ArrayList<SkippedPlanEntry>();
    planEntries.stream()
        .map(entry -> new SkippedPlanEntry(entry.name(), normalizedKind(entry.kind()), entry.reason()))
        .forEach(entries::add);
    entries.addAll(sourceEntries);
    return List.copyOf(entries);
  }

  private String normalizedKind(String kind) {
    return kind.strip().toLowerCase(Locale.ROOT);
  }

  private BootstrapPolicy mapPolicy(Optional<PolicyDocument> policy) {
    return policy
        .map(
            value ->
                new BootstrapPolicy(
                    value.dryRun(), value.continueOnError(), value.requireSudo()))
        .orElseGet(BootstrapPolicy::empty);
  }

  private Phase manifestPhase(List<PlanEntryDocument> plan, BootstrapPolicy policy) {
    return new Phase(
        new PhaseName("manifest-plan"),
        "WorkstationProfile plan",
        mapPlanModules(plan, policy),
        List.of(),
        new RestartPolicy.None());
  }

  private List<BootstrapModule> mapPlanModules(
      List<PlanEntryDocument> plan, BootstrapPolicy policy) {
    var modules = new ArrayList<BootstrapModule>();
    for (PlanEntryDocument entry : plan) {
      mapPlanModule(entry, policy).ifPresent(modules::add);
    }
    return List.copyOf(modules);
  }

  private Optional<BootstrapModule> mapPlanModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    return switch (planKind(entry)) {
      case "apt-packages" -> Optional.of(packageModule(entry, PackageManagerKind.APT, policy));
      case "dnf-packages" -> Optional.of(packageModule(entry, PackageManagerKind.DNF, policy));
      case "pacman-packages" ->
          Optional.of(packageModule(entry, PackageManagerKind.PACMAN, policy));
      case "zypper-packages" ->
          Optional.of(packageModule(entry, PackageManagerKind.ZYPPER, policy));
      case "flatpak-packages" -> Optional.of(flatpakModule(entry));
      default -> Optional.empty();
    };
  }

  private PackageModule packageModule(
      PlanEntryDocument entry, PackageManagerKind kind, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new PackageModule(
        new ModuleName(planName(entry)),
        kind,
        packageNames(spec),
        packageActions(spec),
        continueOnError(entry, policy));
  }

  private boolean continueOnError(PlanEntryDocument entry, BootstrapPolicy policy) {
    return entry
        .execution()
        .flatMap(execution -> execution.continueOnError())
        .or(policy::continueOnErrorDefault)
        .orElse(true);
  }

  private List<PackageName> packageNames(PlanSpecDocument spec) {
    return spec.packages().stream().map(PackageName::new).toList();
  }

  private List<PackageManagerAction> packageActions(PlanSpecDocument spec) {
    return spec.actions().stream()
        .map(
            action ->
                new PackageManagerAction(
                    action.action().orElseThrow().toLowerCase(Locale.ROOT), action.args()))
        .toList();
  }

  private FlatpakModule flatpakModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new FlatpakModule(
        new ModuleName(planName(entry)), spec.remote().orElse("flathub"), appIds(spec));
  }

  private List<String> appIds(PlanSpecDocument spec) {
    var appIds = new ArrayList<String>();
    appIds.addAll(spec.apps());
    appIds.addAll(spec.appIds());
    return List.copyOf(appIds);
  }

  private String planKind(PlanEntryDocument entry) {
    return requireField(entry.kind().orElse(null), planName(entry) + ".kind")
        .strip()
        .toLowerCase(Locale.ROOT);
  }

  private String planName(PlanEntryDocument entry) {
    return requireField(entry.name().orElse(null), "spec.plan[].name");
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
