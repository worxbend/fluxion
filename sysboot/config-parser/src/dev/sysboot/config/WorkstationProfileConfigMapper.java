package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.config.yaml.contract.MetadataDocument;
import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.TargetDocument;
import dev.sysboot.config.yaml.contract.TargetOsDocument;
import dev.sysboot.config.yaml.contract.WhenDocument;
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
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
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
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.SdkmanPackage;
import dev.sysboot.core.SkippedPlanEntry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class WorkstationProfileConfigMapper {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
      case "aur-packages" -> Optional.of(packageModule(entry, aurPackageManager(entry), policy));
      case "cargo-packages" ->
          Optional.of(packageModule(entry, PackageManagerKind.CARGO, policy));
      case "dnf-packages" -> Optional.of(packageModule(entry, PackageManagerKind.DNF, policy));
      case "pacman-packages" ->
          Optional.of(packageModule(entry, PackageManagerKind.PACMAN, policy));
      case "sdkman-packages" -> Optional.of(sdkmanModule(entry, policy));
      case "zypper-packages" ->
          Optional.of(packageModule(entry, PackageManagerKind.ZYPPER, policy));
      case "flatpak-packages" -> Optional.of(flatpakModule(entry, policy));
      case "binary-downloads" -> Optional.of(compiledBinaryModule(entry, policy));
      case "shell-scripts" -> Optional.of(shellScriptModule(entry, policy));
      case "commands" -> Optional.of(shellCommandModule(entry, policy));
      case "nerd-fonts" -> Optional.of(nerdFontModule(entry));
      case "dotfiles-apply" -> Optional.of(dotbotModule(entry));
      case "interrupt" -> Optional.of(interruptModule(entry));
      default -> Optional.empty();
    };
  }

  private InterruptModule interruptModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = entry.spec().orElse(null);
    String name = planName(entry);
    return new InterruptModule(
        new ModuleName(name),
        interruptMessage(name, spec),
        spec == null ? List.of() : spec.instructions(),
        resumeMode(spec),
        spec == null ? 75 : spec.exitCode().orElse(75));
  }

  private String interruptMessage(String name, PlanSpecDocument spec) {
    if (spec == null || spec.message().isEmpty()) {
      return "Execution paused by interrupt entry: " + name;
    }
    return spec.message().orElseThrow();
  }

  private InterruptResumeMode resumeMode(PlanSpecDocument spec) {
    String raw = spec == null ? "next" : spec.resumeFrom().orElse("next");
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "current" -> InterruptResumeMode.CURRENT;
      case "next" -> InterruptResumeMode.NEXT;
      default -> throw new IllegalArgumentException("Unsupported interrupt resumeFrom: " + raw);
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

  private PackageManagerKind aurPackageManager(PlanEntryDocument entry) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    String packageManager = spec.packageManager().orElseThrow().strip().toLowerCase(Locale.ROOT);
    return switch (packageManager) {
      case "paru" -> PackageManagerKind.PARU;
      case "yay" -> PackageManagerKind.YAY;
      default -> throw new IllegalArgumentException("Unsupported AUR helper: " + packageManager);
    };
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

  private SdkmanModule sdkmanModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new SdkmanModule(
        new ModuleName(planName(entry)), sdkmanPackages(spec), continueOnError(entry, policy));
  }

  private List<SdkmanPackage> sdkmanPackages(PlanSpecDocument spec) {
    return spec.packageItems().stream().map(this::sdkmanPackage).toList();
  }

  private SdkmanPackage sdkmanPackage(JsonNode node) {
    if (node.isTextual()) {
      return new SdkmanPackage(node.asText());
    }
    return new SdkmanPackage(text(node, "candidate").orElseThrow(), text(node, "version"));
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
    return new ShellScriptModule(
        new ModuleName(planName(entry)),
        scriptItems(entry, spec),
        spec.workingDir().map(Path::of),
        continueOnError(entry, policy),
        spec.probeCommand());
  }

  private ShellCommandModule shellCommandModule(
      PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = requireField(entry.spec().orElse(null), planName(entry) + ".spec");
    return new ShellCommandModule(
        new ModuleName(planName(entry)),
        commandItems(entry, spec),
        spec.shell().orElse("/bin/bash"),
        spec.workingDir().map(Path::of),
        continueOnError(entry, policy),
        spec.probeCommand());
  }

  private List<ShellScriptItem> scriptItems(PlanEntryDocument entry, PlanSpecDocument spec) {
    List<JsonNode> nodes = spec.scriptItems();
    if (nodes.isEmpty()) {
      return List.of(scriptItem(entry, spec, null, 0));
    }
    var items = new ArrayList<ShellScriptItem>();
    for (int index = 0; index < nodes.size(); index++) {
      if (itemMatches(nodes.get(index))) {
        items.add(scriptItem(entry, spec, nodes.get(index), index));
      }
    }
    return List.copyOf(items);
  }

  private ShellScriptItem scriptItem(
      PlanEntryDocument entry, PlanSpecDocument spec, JsonNode node, int index) {
    String name = text(node, "name").orElse(planName(entry) + "[" + index + "]");
    Optional<ScriptPath> script = scriptPath(node, spec);
    Optional<URI> url = text(node, "url").or(spec::url).map(URI::create);
    return new ShellScriptItem(
        name,
        script,
        url,
        stringList(node, "args").orElseGet(spec::args),
        path(node, "cwd").or(() -> path(node, "workingDir")).or(() -> spec.workingDir().map(Path::of)),
        environment(spec.envNode(), child(node, "env")),
        bool(node, "sudo").or(spec::sudo).orElse(false),
        intList(node, "allowedExitCodes").orElseGet(spec::allowedExitCodes),
        path(node, "creates").or(() -> spec.creates().map(Path::of)),
        text(node, "unless").or(spec::unless),
        confirm(node).or(spec::confirm),
        timeout(node).orElseGet(() -> timeout(spec)));
  }

  private Optional<ScriptPath> scriptPath(JsonNode node, PlanSpecDocument spec) {
    return text(node, "script").or(spec::script)
        .map(raw -> new ScriptPath(Path.of(expandHome(raw))));
  }

  private List<ShellCommandItem> commandItems(PlanEntryDocument entry, PlanSpecDocument spec) {
    List<JsonNode> nodes = spec.commandItems();
    var items = new ArrayList<ShellCommandItem>();
    for (int index = 0; index < nodes.size(); index++) {
      if (itemMatches(nodes.get(index))) {
        items.add(commandItem(entry, spec, nodes.get(index), index));
      }
    }
    return List.copyOf(items);
  }

  private ShellCommandItem commandItem(
      PlanEntryDocument entry, PlanSpecDocument spec, JsonNode node, int index) {
    String fallback = node.isTextual() ? node.asText() : planName(entry) + "[" + index + "]";
    String name = text(node, "name").orElse(fallback);
    return new ShellCommandItem(
        name,
        shellCommand(node),
        argv(node),
        text(node, "shell").or(spec::shell).orElse("/bin/bash"),
        path(node, "cwd").or(() -> path(node, "workingDir")).or(() -> spec.workingDir().map(Path::of)),
        environment(spec.envNode(), child(node, "env")),
        bool(node, "sudo").or(spec::sudo).orElse(false),
        intList(node, "allowedExitCodes").orElseGet(spec::allowedExitCodes),
        path(node, "creates").or(() -> spec.creates().map(Path::of)),
        text(node, "unless").or(spec::unless),
        confirm(node).or(spec::confirm),
        timeout(node).orElseGet(() -> timeout(spec)));
  }

  private Optional<String> shellCommand(JsonNode node) {
    if (node.isTextual()) {
      return Optional.of(node.asText());
    }
    return text(node, "run").or(() -> text(node, "shellCommand"));
  }

  private Optional<List<String>> argv(JsonNode node) {
    if (node.isArray()) {
      return Optional.of(stringArray(node));
    }
    return array(node, "run").or(() -> array(node, "argv")).or(() -> commandWithArgs(node));
  }

  private Optional<List<String>> commandWithArgs(JsonNode node) {
    return text(node, "command")
        .map(command -> {
          var values = new ArrayList<String>();
          values.add(command);
          values.addAll(stringList(node, "args").orElse(List.of()));
          return List.copyOf(values);
        });
  }

  private List<ShellEnvironmentVariable> environment(
      Optional<JsonNode> moduleEnv, Optional<JsonNode> itemEnv) {
    var values = new java.util.LinkedHashMap<String, ShellEnvironmentVariable>();
    moduleEnv.ifPresent(env -> addEnvironment(values, env));
    itemEnv.ifPresent(env -> addEnvironment(values, env));
    return List.copyOf(values.values());
  }

  private void addEnvironment(
      Map<String, ShellEnvironmentVariable> values, JsonNode env) {
    if (env == null || !env.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = env.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      environmentVariable(field.getKey(), field.getValue()).ifPresent(value -> values.put(field.getKey(), value));
    }
  }

  private Optional<ShellEnvironmentVariable> environmentVariable(String name, JsonNode value) {
    if (value.isTextual()) {
      return Optional.of(new ShellEnvironmentVariable(name, value.asText(), sensitiveName(name)));
    }
    if (!value.isObject()) {
      return Optional.empty();
    }
    return text(value, "value")
        .map(raw -> new ShellEnvironmentVariable(name, raw, bool(value, "sensitive").orElse(sensitiveName(name))));
  }

  private boolean sensitiveName(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    return normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("password")
        || normalized.contains("passwd")
        || normalized.contains("credential");
  }

  private boolean itemMatches(JsonNode node) {
    return child(node, "when").map(value -> whenEvaluator.matches(Optional.of(MAPPER.convertValue(value, WhenDocument.class)))).orElse(true);
  }

  private Optional<JsonNode> child(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return Optional.empty();
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
  }

  private Optional<String> text(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isTextual).map(JsonNode::asText).filter(value -> !value.isBlank());
  }

  private Optional<Boolean> bool(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isBoolean).map(JsonNode::asBoolean);
  }

  private Optional<Path> path(JsonNode node, String field) {
    return text(node, field).map(value -> Path.of(expandHome(value)));
  }

  private Optional<List<Integer>> intList(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isArray).map(values -> {
      var result = new ArrayList<Integer>();
      values.forEach(value -> {
        if (value.canConvertToInt()) {
          result.add(value.asInt());
        }
      });
      return List.copyOf(result);
    });
  }

  private Optional<List<String>> stringList(JsonNode node, String field) {
    return child(node, field).filter(JsonNode::isArray).map(this::stringArray);
  }

  private Optional<List<String>> array(JsonNode node, String field) {
    return stringList(node, field).filter(values -> !values.isEmpty());
  }

  private List<String> stringArray(JsonNode node) {
    var values = new ArrayList<String>();
    node.forEach(value -> {
      if (value.isTextual() && !value.asText().isBlank()) {
        values.add(value.asText());
      }
    });
    return List.copyOf(values);
  }

  private Optional<String> confirm(JsonNode node) {
    return text(node, "confirm")
        .or(() -> bool(node, "confirm").filter(Boolean::booleanValue).map(ignored -> "confirm"));
  }

  private Optional<Duration> timeout(JsonNode node) {
    return text(node, "timeout").map(this::duration)
        .or(() -> child(node, "timeoutSeconds").filter(JsonNode::canConvertToInt).map(value -> Duration.ofSeconds(value.asInt())));
  }

  private Duration timeout(PlanSpecDocument spec) {
    return spec.timeout().map(this::duration)
        .or(() -> spec.timeoutSeconds().map(Duration::ofSeconds))
        .orElse(Duration.ofMinutes(30));
  }

  private Duration duration(String raw) {
    String value = raw.strip().toLowerCase(Locale.ROOT);
    if (value.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(value));
    }
    if (value.endsWith("ms")) {
      return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
    }
    if (value.endsWith("s")) {
      return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    if (value.endsWith("m")) {
      return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    return Duration.parse(raw);
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
