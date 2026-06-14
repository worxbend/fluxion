package dev.sysboot.config;

import dev.sysboot.config.dto.ChecksumDto;
import dev.sysboot.config.dto.CompiledBinaryModuleDto;
import dev.sysboot.config.dto.ConfigDto;
import dev.sysboot.config.dto.DefaultShellModuleDto;
import dev.sysboot.config.dto.DotbotModuleDto;
import dev.sysboot.config.dto.FlatpakModuleDto;
import dev.sysboot.config.dto.ModuleDto;
import dev.sysboot.config.dto.NerdFontModuleDto;
import dev.sysboot.config.dto.OhMyZshModuleDto;
import dev.sysboot.config.dto.OsDto;
import dev.sysboot.config.dto.PackagesModuleDto;
import dev.sysboot.config.dto.PhaseDto;
import dev.sysboot.config.dto.RestartPolicyDto;
import dev.sysboot.config.dto.ShellCommandModuleDto;
import dev.sysboot.config.dto.ShellReloadModuleDto;
import dev.sysboot.config.dto.ShellScriptModuleDto;
import dev.sysboot.config.dto.ToolchainModuleDto;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
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

  BootstrapConfig map(ConfigDto dto, Path configFile) {
    validateSchemaVersion(dto.schemaVersion);
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName(requireField(dto.profile, "profile")))
            .target(mapOs(requireField(dto.os, "os")));

    List<PhaseDto> phaseDtos = phaseDtos(dto);
    if (!phaseDtos.isEmpty()) {
      for (PhaseDto phaseDto : phaseDtos) {
        builder.addPhase(mapPhase(phaseDto, configFile));
      }
    } else {
      List<ModuleDto> moduleDtos = dto.modules != null ? dto.modules : List.of();
      for (ModuleDto moduleDto : moduleDtos) {
        builder.addModule(mapModule(moduleDto, configFile));
      }
    }

    return builder.build();
  }

  private List<PhaseDto> phaseDtos(ConfigDto dto) {
    if (dto.jobs != null && !dto.jobs.isEmpty()) {
      return dto.jobs;
    }
    return dto.phases != null ? dto.phases : List.of();
  }

  private Phase mapPhase(PhaseDto dto, Path configFile) {
    String name = requireField(dto.name, "phase.name");
    List<PhaseName> deps =
        dto.dependsOn != null ? dto.dependsOn.stream().map(PhaseName::new).toList() : List.of();
    RestartPolicy policy =
        dto.restartPolicy != null ? mapRestartPolicy(dto.restartPolicy) : new RestartPolicy.None();
    List<BootstrapModule> modules = new ArrayList<>();
    for (ModuleDto moduleDto : stepDtos(dto)) {
      modules.add(mapModule(moduleDto, configFile));
    }
    return new Phase(
        new PhaseName(name),
        dto.description != null ? dto.description : "",
        modules,
        deps,
        policy,
        dto.continueOnModuleError);
  }

  private List<ModuleDto> stepDtos(PhaseDto dto) {
    if (dto.steps != null && !dto.steps.isEmpty()) {
      return dto.steps;
    }
    return dto.modules != null ? dto.modules : List.of();
  }

  private RestartPolicy mapRestartPolicy(RestartPolicyDto dto) {
    return switch (dto) {
      case RestartPolicyDto.NoneDto ignored -> new RestartPolicy.None();
      case RestartPolicyDto.PromptLogoutDto pr ->
          new RestartPolicy.PromptLogout(pr.message != null ? pr.message : "");
      case RestartPolicyDto.RequiresNewShellDto rns ->
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

  private OsTarget mapOs(OsDto dto) {
    String type = requireField(dto.type, "os.type").toLowerCase();
    return switch (type) {
      case "fedora" -> new OsTarget.FedoraTarget(dto.release != null ? dto.release : "");
      case "arch" -> new OsTarget.ArchTarget();
      case "opensuse" -> new OsTarget.OpenSuseTarget(dto.release != null ? dto.release : "");
      case "debian", "ubuntu" -> new OsTarget.DebianTarget(dto.release != null ? dto.release : "");
      default -> throw new IllegalArgumentException("Unsupported OS type: " + type);
    };
  }

  private BootstrapModule mapModule(ModuleDto dto, Path configFile) {
    return switch (dto) {
      case PackagesModuleDto pm -> mapPackagesModule(pm);
      case FlatpakModuleDto fm -> mapFlatpakModule(fm);
      case ShellScriptModuleDto sm -> mapShellScriptModule(sm, configFile);
      case CompiledBinaryModuleDto bm -> mapCompiledBinaryModule(bm);
      case DotbotModuleDto db -> mapDotbotModule(db, configFile);
      case DefaultShellModuleDto ds -> mapDefaultShellModule(ds);
      case OhMyZshModuleDto omz -> mapOhMyZshModule(omz);
      case ToolchainModuleDto tc -> mapToolchainModule(tc);
      case NerdFontModuleDto nf -> mapNerdFontModule(nf);
      case ShellReloadModuleDto sr -> mapShellReloadModule(sr);
      case ShellCommandModuleDto sc -> mapShellCommandModule(sc);
    };
  }

  private PackageModule mapPackagesModule(PackagesModuleDto dto) {
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

  private FlatpakModule mapFlatpakModule(FlatpakModuleDto dto) {
    String remote = dto.remote != null ? dto.remote : "flathub";
    return new FlatpakModule(
        new ModuleName(requireField(dto.name, "name")), remote, requireField(dto.appIds, "appIds"));
  }

  private ShellScriptModule mapShellScriptModule(ShellScriptModuleDto dto, Path configFile) {
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

  private CompiledBinaryModule mapCompiledBinaryModule(CompiledBinaryModuleDto dto) {
    var url = new BinaryUrl(URI.create(requireField(dto.url, "url")));
    var installPath = absolutePath(requireField(dto.installPath, "installPath"), "installPath");
    return new CompiledBinaryModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.binaryName, "binaryName"),
        url,
        mapChecksum(dto.checksum),
        installPath,
        dto.continueOnError,
        Optional.ofNullable(dto.versionCommand),
        Optional.ofNullable(dto.expectedVersion));
  }

  private DotbotModule mapDotbotModule(DotbotModuleDto dto, Path configFile) {
    String rawConfig = requireField(dto.config, "dotbot.config");
    Path configPath = Path.of(rawConfig.replace("~", System.getProperty("user.home")));
    return new DotbotModule(
        new ModuleName(requireField(dto.name, "name")),
        configPath,
        requireField(dto.installerVersion, "dotbot.installerVersion"),
        dto.dotbotBinary != null ? dto.dotbotBinary : "dotbot",
        Optional.ofNullable(dto.probeCommand));
  }

  private DefaultShellModule mapDefaultShellModule(DefaultShellModuleDto dto) {
    String shell = dto.shell != null ? dto.shell : dto.shellPath;
    return new DefaultShellModule(
        new ModuleName(requireField(dto.name, "name")),
        Path.of(requireField(shell, "default-shell.shell")),
        Optional.ofNullable(dto.probeCommand));
  }

  private OhMyZshModule mapOhMyZshModule(OhMyZshModuleDto dto) {
    String dir = dto.installDir != null ? dto.installDir : "~/.oh-my-zsh";
    return new OhMyZshModule(
        new ModuleName(requireField(dto.name, "name")),
        Path.of(dir.replace("~", System.getProperty("user.home"))),
        Optional.ofNullable(dto.probeCommand));
  }

  private ToolchainModule mapToolchainModule(ToolchainModuleDto dto) {
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

  private NerdFontModule mapNerdFontModule(NerdFontModuleDto dto) {
    var cfgDto = requireField(dto.config, "nerd-fonts.config");
    var dest =
        cfgDto.destination != null
            ? Path.of(cfgDto.destination.replace("~", System.getProperty("user.home")))
            : Path.of(System.getProperty("user.home"), ".local/share/fonts/NerdFonts");
    var config =
        new NerdFontConfig(
            cfgDto.release != null ? cfgDto.release : "latest",
            dest,
            cfgDto.refreshFontCache,
            cfgDto.families != null ? cfgDto.families : List.of());
    return new NerdFontModule(
        new ModuleName(requireField(dto.name, "name")),
        requireField(dto.installerVersion, "nerd-fonts.installerVersion"),
        dto.nerdfontBinary != null ? dto.nerdfontBinary : "nerdfont-install",
        config,
        Optional.ofNullable(dto.probeCommand));
  }

  private ShellReloadModule mapShellReloadModule(ShellReloadModuleDto dto) {
    return new ShellReloadModule(
        new ModuleName(requireField(dto.name, "name")),
        mapShellKind(dto.shell),
        dto.description != null ? dto.description : "");
  }

  private ShellCommandModule mapShellCommandModule(ShellCommandModuleDto dto) {
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

  private Optional<Checksum> mapChecksum(ChecksumDto dto) {
    if (dto == null) return Optional.empty();
    return Optional.of(
        new Checksum(
            requireField(dto.algorithm, "checksum.algorithm"),
            requireField(dto.value, "checksum.value")));
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
