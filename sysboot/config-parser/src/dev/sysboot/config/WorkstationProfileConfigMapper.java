package dev.sysboot.config;

import static dev.sysboot.config.MappingSupport.requireField;

import dev.sysboot.config.yaml.contract.MetadataDocument;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.TargetDocument;
import dev.sysboot.config.yaml.contract.TargetOsDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.HostFactsProvider;
import dev.sysboot.core.OsTarget;
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
  private final WorkstationMappingSupport mappingSupport;

  WorkstationProfileConfigMapper(HostFactsProvider hostFactsProvider) {
    this(
        new WorkstationProfileValidator(),
        new WorkstationProfileWhenEvaluator(hostFactsProvider),
        new WorkstationProfileSourceMapper());
  }

  WorkstationProfileConfigMapper(
      WorkstationProfileValidator validator,
      WorkstationProfileWhenEvaluator whenEvaluator,
      WorkstationProfileSourceMapper sourceMapper) {
    this.validator = validator;
    this.whenEvaluator = whenEvaluator;
    this.sourceMapper = sourceMapper;
    this.mappingSupport = new WorkstationMappingSupport();
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
    Path manifestDirectory =
        Optional.ofNullable(manifestPath.toAbsolutePath().normalize().getParent())
            .orElseThrow(
                () -> new IllegalArgumentException("Workstation manifest must have a directory"));
    var planMappers = new WorkstationPlanMappers(whenEvaluator, mappingSupport, manifestDirectory);
    return BootstrapConfig.builder()
        .profileName(new ProfileName(requireField(metadata.name().orElse(null), "metadata.name")))
        .target(mapTarget(requireField(target, "spec.target")))
        .policy(policy)
        .skippedPlanEntries(skippedEntries(selection.skipped(), sourceMapping.skippedEntries()))
        .sourceSetups(sourceMapping.sourceSetups())
        .addPhase(manifestPhase(selection.selected(), policy, planMappers))
        .build();
  }

  private List<SkippedPlanEntry> skippedEntries(
      List<WorkstationProfileWhenEvaluator.SkippedPlanEntry> planEntries,
      List<SkippedPlanEntry> sourceEntries) {
    var entries = new ArrayList<SkippedPlanEntry>();
    planEntries.stream()
        .map(
            entry ->
                new SkippedPlanEntry(entry.name(), normalizedKind(entry.kind()), entry.reason()))
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
                new BootstrapPolicy(value.dryRun(), value.continueOnError(), value.requireSudo()))
        .orElseGet(BootstrapPolicy::empty);
  }

  private Phase manifestPhase(
      List<PlanEntryDocument> plan, BootstrapPolicy policy, WorkstationPlanMappers planMappers) {
    return new Phase(
        new PhaseName("manifest-plan"),
        "WorkstationProfile plan",
        mapPlanModules(plan, policy, planMappers),
        List.of(),
        new RestartPolicy.None(),
        false);
  }

  private List<BootstrapModule> mapPlanModules(
      List<PlanEntryDocument> plan, BootstrapPolicy policy, WorkstationPlanMappers planMappers) {
    var modules = new ArrayList<BootstrapModule>();
    for (PlanEntryDocument entry : plan) {
      mapPlanModule(entry, policy, planMappers).ifPresent(modules::add);
    }
    return List.copyOf(modules);
  }

  private Optional<BootstrapModule> mapPlanModule(
      PlanEntryDocument entry, BootstrapPolicy policy, WorkstationPlanMappers planMappers) {
    return PlanKinds.find(planKind(entry))
        .flatMap(kind -> kind.mapper().map(planMappers, entry, policy));
  }

  private String planKind(PlanEntryDocument entry) {
    String name = requireField(entry.name().orElse(null), "spec.plan[].name");
    return requireField(entry.kind().orElse(null), name + ".kind").strip().toLowerCase(Locale.ROOT);
  }

  private OsTarget mapTarget(TargetDocument target) {
    TargetOsDocument os = requireField(target.os().orElse(null), "spec.target.os");
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
}
