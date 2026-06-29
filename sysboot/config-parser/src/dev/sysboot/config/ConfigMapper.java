package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.AptRepositoryModuleDocument;
import dev.sysboot.config.yaml.contract.AssertModuleDocument;
import dev.sysboot.config.yaml.contract.ChecksumDocument;
import dev.sysboot.config.yaml.contract.CompiledBinaryModuleDocument;
import dev.sysboot.config.yaml.contract.ConfigDocument;
import dev.sysboot.config.yaml.contract.DefaultShellModuleDocument;
import dev.sysboot.config.yaml.contract.DotbotModuleDocument;
import dev.sysboot.config.yaml.contract.FlatpakModuleDocument;
import dev.sysboot.config.yaml.contract.FlatpakRemoteModuleDocument;
import dev.sysboot.config.yaml.contract.ManualModuleDocument;
import dev.sysboot.config.yaml.contract.ModuleDocument;
import dev.sysboot.config.yaml.contract.NerdFontModuleDocument;
import dev.sysboot.config.yaml.contract.OhMyZshModuleDocument;
import dev.sysboot.config.yaml.contract.OsDocument;
import dev.sysboot.config.yaml.contract.PackagesModuleDocument;
import dev.sysboot.config.yaml.contract.PacmanRepositoryModuleDocument;
import dev.sysboot.config.yaml.contract.PhaseDocument;
import dev.sysboot.config.yaml.contract.RestartPolicyDocument;
import dev.sysboot.config.yaml.contract.RpmRepositoryModuleDocument;
import dev.sysboot.config.yaml.contract.ShellCommandModuleDocument;
import dev.sysboot.config.yaml.contract.ShellReloadModuleDocument;
import dev.sysboot.config.yaml.contract.ShellScriptModuleDocument;
import dev.sysboot.config.yaml.contract.ToolchainModuleDocument;
import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainKind;
import dev.sysboot.core.ToolchainModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ConfigMapper {

  BootstrapConfig map(ConfigDocument dto, Path configFile) {
    validateSchemaVersion(dto.schemaVersion);
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName(requireField(dto.profile, "profile")))
            .target(mapOs(requireField(dto.os, "os")));

    List<PhaseDocument> phaseDocuments = phaseDocuments(dto);
    if (!phaseDocuments.isEmpty()) {
      for (PhaseDocument phaseDocument : phaseDocuments) {
        builder.addPhase(mapPhase(phaseDocument, configFile));
      }
    } else {
      List<ModuleDocument> moduleDocuments = dto.modules != null ? dto.modules : List.of();
      for (ModuleDocument moduleDocument : moduleDocuments) {
        builder.addModule(mapModule(moduleDocument, configFile));
      }
    }

    return builder.build();
  }

  private List<PhaseDocument> phaseDocuments(ConfigDocument dto) {
    if (dto.jobs != null && !dto.jobs.isEmpty()) {
      return dto.jobs;
    }
    return dto.phases != null ? dto.phases : List.of();
  }

  private Phase mapPhase(PhaseDocument dto, Path configFile) {
    String name = requireField(dto.name, "phase.name");
    List<PhaseName> deps =
        dto.dependsOn != null ? dto.dependsOn.stream().map(PhaseName::new).toList() : List.of();
    RestartPolicy policy =
        dto.restartPolicy != null ? mapRestartPolicy(dto.restartPolicy) : new RestartPolicy.None();
    List<BootstrapModule> modules = new ArrayList<>();
    for (ModuleDocument moduleDocument : stepDocuments(dto)) {
      modules.add(mapModule(moduleDocument, configFile));
    }
    return new Phase(
        new PhaseName(name),
        dto.description != null ? dto.description : "",
        modules,
        deps,
        policy,
        dto.continueOnModuleError);
  }

  private List<ModuleDocument> stepDocuments(PhaseDocument dto) {
    if (dto.steps != null && !dto.steps.isEmpty()) {
      return dto.steps;
    }
    return dto.modules != null ? dto.modules : List.of();
  }

  private RestartPolicy mapRestartPolicy(RestartPolicyDocument dto) {
    return switch (dto) {
      case RestartPolicyDocument.NoneDocument ignored -> new RestartPolicy.None();
      case RestartPolicyDocument.PromptLogoutDocument pr ->
          new RestartPolicy.PromptLogout(pr.message != null ? pr.message : "");
      case RestartPolicyDocument.RequiresNewShellDocument rns ->
          new RestartPolicy.RequiresNewShell(mapShellKind(rns.shell));
    };
  }

  private ShellKind mapShellKind(String shell) {
    if (shell == null) return ShellKind.ZSH;
    return switch (shell.toLowerCase()) {
      case "bash" -> ShellKind.BASH;
      case "sh" -> ShellKind.SH;
      default -> ShellKind.ZSH;
    };
  }

  private OsTarget mapOs(OsDocument dto) {
    String type = requireField(dto.type, "os.type").toLowerCase();
    return switch (type) {
      case "fedora" -> new OsTarget.FedoraTarget(dto.release != null ? dto.release : "");
      case "arch" -> new OsTarget.ArchTarget();
      case "opensuse" -> new OsTarget.OpenSuseTarget(dto.release != null ? dto.release : "");
      case "debian", "ubuntu" -> new OsTarget.DebianTarget(dto.release != null ? dto.release : "");
      default -> throw new IllegalArgumentException("Unsupported OS type: " + type);
    };
  }

  private BootstrapModule mapModule(ModuleDocument dto, Path configFile) {
    return switch (dto) {
      case PackagesModuleDocument pm -> mapPackagesModule(pm);
      case AptRepositoryModuleDocument arm -> mapAptRepositoryModule(arm);
      case RpmRepositoryModuleDocument rrm -> mapRpmRepositoryModule(rrm);
      case PacmanRepositoryModuleDocument prm -> mapPacmanRepositoryModule(prm);
      case FlatpakModuleDocument fm -> mapFlatpakModule(fm);
      case FlatpakRemoteModuleDocument frm -> mapFlatpakRemoteModule(frm);
      case ShellScriptModuleDocument sm -> mapShellScriptModule(sm, configFile);
      case CompiledBinaryModuleDocument bm -> mapCompiledBinaryModule(bm);
      case DotbotModuleDocument db -> mapDotbotModule(db, configFile);
      case DefaultShellModuleDocument ds -> mapDefaultShellModule(ds);
      case OhMyZshModuleDocument omz -> mapOhMyZshModule(omz);
      case ToolchainModuleDocument tc -> mapToolchainModule(tc);
      case NerdFontModuleDocument nf -> mapNerdFontModule(nf);
      case ShellReloadModuleDocument sr -> mapShellReloadModule(sr);
      case ShellCommandModuleDocument sc -> mapShellCommandModule(sc);
      case AssertModuleDocument am -> mapAssertModule(am);
      case ManualModuleDocument mm -> mapManualModule(mm);
    };
  }

  private PackageModule mapPackagesModule(PackagesModuleDocument dto) {
    var kind =
        PackageManagerKind.valueOf(
            requireField(dto.packageManager, "packageManager").toUpperCase());
    List<PackageName> packages = new ArrayList<>();
    for (String pkg : requireField(dto.packages, "packages")) {
      packages.add(new PackageName(pkg));
    }
    return new PackageModule(
        new ModuleName(requireField(dto.name, "name")), kind, packages, dto.continueOnError);
  }

  private AptRepositoryModule mapAptRepositoryModule(AptRepositoryModuleDocument dto) {
    String name = requireField(dto.name, "apt-repository.name");
    Optional<URI> keyUrl = mapUri(dto.signingKeyUrl);
    Optional<Path> keyring =
        Optional.ofNullable(dto.keyring)
            .map(Path::of)
            .or(() -> keyUrl.map(ignored -> Path.of("/etc/apt/keyrings/" + name + ".gpg")));
    return new AptRepositoryModule(
        new ModuleName(name),
        requireField(dto.source, "apt-repository.source"),
        Path.of(
            dto.sourceList != null ? dto.sourceList : "/etc/apt/sources.list.d/" + name + ".list"),
        keyUrl,
        keyring);
  }

  private RpmRepositoryModule mapRpmRepositoryModule(RpmRepositoryModuleDocument dto) {
    String name = requireField(dto.name, "rpm-repository.name");
    return new RpmRepositoryModule(
        new ModuleName(name),
        dto.id != null ? dto.id : name,
        URI.create(requireField(dto.baseUrl, "rpm-repository.baseUrl")),
        Path.of(dto.repoFile != null ? dto.repoFile : "/etc/yum.repos.d/" + name + ".repo"),
        mapUri(dto.gpgKeyUrl),
        dto.enabled == null || dto.enabled,
        dto.gpgCheck == null || dto.gpgCheck);
  }

  private PacmanRepositoryModule mapPacmanRepositoryModule(PacmanRepositoryModuleDocument dto) {
    String name = requireField(dto.name, "pacman-repository.name");
    return new PacmanRepositoryModule(
        new ModuleName(name),
        dto.repository != null ? dto.repository : name,
        URI.create(requireField(dto.server, "pacman-repository.server")),
        Path.of(dto.config != null ? dto.config : "/etc/pacman.conf"),
        Optional.ofNullable(dto.sigLevel),
        Optional.ofNullable(dto.include).map(Path::of),
        dto.enabled == null || dto.enabled);
  }

  private FlatpakModule mapFlatpakModule(FlatpakModuleDocument dto) {
    String remote = dto.remote != null ? dto.remote : "flathub";
    return new FlatpakModule(
        new ModuleName(requireField(dto.name, "name")), remote, requireField(dto.appIds, "appIds"));
  }

  private FlatpakRemoteModule mapFlatpakRemoteModule(FlatpakRemoteModuleDocument dto) {
    boolean system = dto.system == null || dto.system;
    return new FlatpakRemoteModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.remote, "flatpak-remote.remote"),
        URI.create(requireField(dto.url, "flatpak-remote.url")),
        system);
  }

  private ShellScriptModule mapShellScriptModule(ShellScriptModuleDocument dto, Path configFile) {
    var scriptPath =
        new ScriptPath(Path.of(requireField(dto.script, "script"))).resolve(configFile.getParent());
    var args = dto.args != null ? dto.args : List.<String>of();
    var workingDir =
        dto.workingDir != null ? Optional.of(Path.of(dto.workingDir)) : Optional.<Path>empty();
    return new ShellScriptModule(
        new ModuleName(requireField(dto.name, "name")),
        scriptPath,
        args,
        workingDir,
        dto.continueOnError,
        Optional.ofNullable(dto.probeCommand));
  }

  private CompiledBinaryModule mapCompiledBinaryModule(CompiledBinaryModuleDocument dto) {
    var url = new BinaryUrl(URI.create(requireField(dto.url, "url")));
    var installPath = absolutePath(requireField(dto.installPath, "installPath"), "installPath");
    return new CompiledBinaryModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.binaryName, "binaryName"),
        url,
        mapChecksum(dto.checksum),
        mapBinaryUrl(dto.checksumUrl),
        mapBinaryUrl(dto.signatureUrl),
        installPath,
        Optional.ofNullable(dto.archivePath),
        dto.stripComponents != null ? dto.stripComponents : 0,
        Optional.ofNullable(binaryMode(dto)).or(() -> Optional.of("0755")),
        mapSymlinkPath(dto),
        dto.continueOnError,
        Optional.ofNullable(dto.versionCommand),
        Optional.ofNullable(dto.expectedVersion));
  }

  private String binaryMode(CompiledBinaryModuleDocument dto) {
    return dto.installMode != null ? dto.installMode : dto.mode;
  }

  private Optional<Path> mapSymlinkPath(CompiledBinaryModuleDocument dto) {
    String rawPath = dto.symlinkPath != null ? dto.symlinkPath : dto.symlink;
    return Optional.ofNullable(rawPath).map(path -> absolutePath(path, "symlinkPath"));
  }

  private DotbotModule mapDotbotModule(DotbotModuleDocument dto, Path configFile) {
    String rawConfig = requireField(dto.config, "dotbot.config");
    Path configPath = Path.of(rawConfig.replace("~", System.getProperty("user.home")));
    return new DotbotModule(
        new ModuleName(requireField(dto.name, "name")),
        configPath,
        requireField(dto.installerVersion, "dotbot.installerVersion"),
        dto.dotbotBinary != null ? dto.dotbotBinary : "dotbot",
        Optional.ofNullable(dto.probeCommand));
  }

  private DefaultShellModule mapDefaultShellModule(DefaultShellModuleDocument dto) {
    String shell = dto.shell != null ? dto.shell : dto.shellPath;
    return new DefaultShellModule(
        new ModuleName(requireField(dto.name, "name")),
        Path.of(requireField(shell, "default-shell.shell")),
        Optional.ofNullable(dto.probeCommand));
  }

  private OhMyZshModule mapOhMyZshModule(OhMyZshModuleDocument dto) {
    String dir = dto.installDir != null ? dto.installDir : "~/.oh-my-zsh";
    return new OhMyZshModule(
        new ModuleName(requireField(dto.name, "name")),
        Path.of(dir.replace("~", System.getProperty("user.home"))),
        Optional.ofNullable(dto.probeCommand));
  }

  private ToolchainModule mapToolchainModule(ToolchainModuleDocument dto) {
    var kind = ToolchainKind.valueOf(requireField(dto.kind, "toolchain.kind").toUpperCase());
    String installScript = dto.installScriptUrl != null ? dto.installScriptUrl : dto.installScript;
    return new ToolchainModule(
        new ModuleName(requireField(dto.name, "name")),
        kind,
        requireField(installScript, "toolchain.installScriptUrl"),
        dto.installArgs != null ? dto.installArgs : List.of(),
        Optional.ofNullable(dto.postInstallEnvSource),
        Optional.ofNullable(dto.probeCommand),
        dto.continueOnError);
  }

  private NerdFontModule mapNerdFontModule(NerdFontModuleDocument dto) {
    var configDocument = requireField(dto.config, "nerd-fonts.config");
    var dest =
        configDocument.destination != null
            ? Path.of(configDocument.destination.replace("~", System.getProperty("user.home")))
            : Path.of(System.getProperty("user.home"), ".local/share/fonts/NerdFonts");
    var config =
        new NerdFontConfig(
            configDocument.release != null ? configDocument.release : "latest",
            dest,
            configDocument.refreshFontCache,
            configDocument.families != null ? configDocument.families : List.of());
    return new NerdFontModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.installerVersion, "nerd-fonts.installerVersion"),
        dto.nerdfontBinary != null ? dto.nerdfontBinary : "nerdfont-install",
        config,
        Optional.ofNullable(dto.probeCommand));
  }

  private ShellReloadModule mapShellReloadModule(ShellReloadModuleDocument dto) {
    return new ShellReloadModule(
        new ModuleName(requireField(dto.name, "name")),
        mapShellKind(dto.shell),
        dto.description != null ? dto.description : "");
  }

  private ShellCommandModule mapShellCommandModule(ShellCommandModuleDocument dto) {
    var workingDir =
        dto.workingDir != null ? Optional.of(Path.of(dto.workingDir)) : Optional.<Path>empty();
    return new ShellCommandModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.commands, "shell-command.commands"),
        dto.shell != null ? dto.shell : "/bin/bash",
        workingDir,
        dto.continueOnError,
        Optional.ofNullable(dto.probeCommand));
  }

  private AssertModule mapAssertModule(AssertModuleDocument dto) {
    var workingDir =
        dto.workingDir != null ? Optional.of(Path.of(dto.workingDir)) : Optional.<Path>empty();
    return new AssertModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.command, "assert.command"),
        dto.message != null ? dto.message : "Assertion failed: " + dto.name,
        dto.shell != null ? dto.shell : "/bin/bash",
        workingDir);
  }

  private ManualModule mapManualModule(ManualModuleDocument dto) {
    return new ManualModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.message, "manual.message"),
        Optional.ofNullable(dto.probeCommand));
  }

  private Optional<Checksum> mapChecksum(ChecksumDocument dto) {
    if (dto == null) return Optional.empty();
    return Optional.of(
        new Checksum(
            requireField(dto.algorithm, "checksum.algorithm"),
            requireField(dto.value, "checksum.value")));
  }

  private Optional<BinaryUrl> mapBinaryUrl(String value) {
    return Optional.ofNullable(value).map(url -> new BinaryUrl(URI.create(url)));
  }

  private Optional<URI> mapUri(String value) {
    return Optional.ofNullable(value).map(URI::create);
  }

  private Path absolutePath(String rawPath, String fieldName) {
    Path path = Path.of(expandHome(rawPath));
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("Required field '" + fieldName + "' must be absolute");
    }
    return path;
  }

  private String expandHome(String rawPath) {
    if (rawPath.equals("~")) {
      return System.getProperty("user.home");
    }
    if (rawPath.startsWith("~/")) {
      return System.getProperty("user.home") + rawPath.substring(1);
    }
    return rawPath;
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

  private void validateSchemaVersion(Integer schemaVersion) {
    if (schemaVersion == null || schemaVersion == 1) {
      return;
    }
    throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
  }
}
