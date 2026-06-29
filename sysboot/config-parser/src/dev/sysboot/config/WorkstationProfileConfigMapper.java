package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.MetadataDocument;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.TargetDocument;
import dev.sysboot.config.yaml.contract.TargetOsDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.HostFactsProvider;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SkippedPlanEntry;
import java.net.URI;
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
        new RestartPolicy.None(),
        false);
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
      case "flatpak-packages" -> Optional.of(flatpakModule(entry, policy));
      case "binary-downloads" -> Optional.of(compiledBinaryModule(entry, policy));
      case "shell-scripts" -> Optional.of(shellScriptModule(entry, policy));
      case "commands" -> Optional.of(shellCommandModule(entry, policy));
      case "nerd-fonts" -> Optional.of(nerdFontModule(entry));
      case "dotfiles-apply" -> Optional.of(dotbotModule(entry));
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

  private FlatpakModule flatpakModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new FlatpakModule(
        new ModuleName(planName(entry)),
        spec.remote().orElse("flathub"),
        appIds(spec),
        continueOnError(entry, policy));
  }

  private List<String> appIds(PlanSpecDocument spec) {
    var appIds = new ArrayList<String>();
    appIds.addAll(spec.apps());
    appIds.addAll(spec.appIds());
    return List.copyOf(appIds);
  }

  private CompiledBinaryModule compiledBinaryModule(
      PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new CompiledBinaryModule(
        new ModuleName(planName(entry)),
        requireField(spec.binaryName().orElse(null), planName(entry) + ".spec.binaryName"),
        binaryUrl(requireField(spec.url().orElse(null), planName(entry) + ".spec.url")),
        checksum(spec.checksum()),
        spec.checksumUrl().map(this::binaryUrl),
        spec.signatureUrl().map(this::binaryUrl),
        absolutePath(
            requireField(spec.installPath().orElse(null), planName(entry) + ".spec.installPath")),
        spec.archivePath(),
        spec.stripComponents().orElse(0),
        Optional.of(spec.installMode().orElse("0755")),
        spec.symlinkPath().map(this::absolutePath),
        continueOnError(entry, policy),
        spec.versionCommand(),
        spec.expectedVersion());
  }

  private ShellScriptModule shellScriptModule(
      PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    var script =
        new ScriptPath(
            Path.of(requireField(spec.script().orElse(null), planName(entry) + ".spec.script")));
    return new ShellScriptModule(
        new ModuleName(planName(entry)),
        script,
        spec.args(),
        spec.workingDir().map(Path::of),
        continueOnError(entry, policy),
        spec.probeCommand());
  }

  private ShellCommandModule shellCommandModule(
      PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new ShellCommandModule(
        new ModuleName(planName(entry)),
        spec.commands(),
        spec.shell().orElse("/bin/bash"),
        spec.workingDir().map(Path::of),
        continueOnError(entry, policy),
        spec.probeCommand());
  }

  private NerdFontModule nerdFontModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new NerdFontModule(
        new ModuleName(planName(entry)),
        spec.installerVersion().orElse("v1.0.5"),
        spec.nerdfontBinary().orElse("nerdfont-install"),
        nerdFontConfig(spec),
        spec.probeCommand());
  }

  private DotbotModule dotbotModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new DotbotModule(
        new ModuleName(planName(entry)),
        Path.of(
            expandHome(
                requireField(spec.dotfilesConfig().orElse(null), planName(entry) + ".spec.config"))),
        spec.installerVersion().orElse("v0.2.1"),
        spec.dotbotBinary().orElse("dotbot"),
        spec.probeCommand());
  }

  private NerdFontConfig nerdFontConfig(PlanSpecDocument spec) {
    var config = spec.nerdFontConfig().orElse(null);
    String release = config != null ? config.release : spec.release().orElse("latest");
    String destination = config != null ? config.destination : spec.destination().orElse(null);
    boolean refresh =
        config != null ? config.refreshFontCache : spec.refreshFontCache().orElse(true);
    List<String> families = config != null ? config.families : spec.families();
    return new NerdFontConfig(
        release,
        Path.of(expandHome(destination != null ? destination : "~/.local/share/fonts/NerdFonts")),
        refresh,
        families);
  }

  private Optional<Checksum> checksum(
      Optional<dev.sysboot.config.yaml.contract.WorkstationChecksumDocument> dto) {
    return dto.map(
        value -> new Checksum(value.algorithm().orElseThrow(), value.value().orElseThrow()));
  }

  private BinaryUrl binaryUrl(String rawUrl) {
    return new BinaryUrl(URI.create(rawUrl));
  }

  private Path absolutePath(String rawPath) {
    Path path = Path.of(expandHome(rawPath));
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Required field 'installPath' must be absolute");
    }
    return path;
  }

  private String expandHome(String rawPath) {
    if (rawPath.equals("~")) {
      return System.getProperty("user.home");
    }
    return rawPath.startsWith("~/")
        ? System.getProperty("user.home") + rawPath.substring(1)
        : rawPath;
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
