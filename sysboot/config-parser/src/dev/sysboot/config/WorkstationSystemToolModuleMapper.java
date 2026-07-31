package dev.sysboot.config;

import static dev.sysboot.config.MappingSupport.enumValue;
import static dev.sysboot.config.MappingSupport.expandHome;
import static dev.sysboot.config.MappingSupport.requireField;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GitRepoUpdate;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.SystemdScope;
import dev.sysboot.core.SystemdState;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackageBackend;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class WorkstationSystemToolModuleMapper {

  private final WorkstationMappingSupport support;

  WorkstationSystemToolModuleMapper(WorkstationMappingSupport support) {
    this.support = support;
  }

  InterruptModule interruptModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = entry.spec().orElse(null);
    String name = support.planName(entry);
    String message =
        spec == null || spec.message().isEmpty()
            ? "Execution paused by interrupt entry: " + name
            : spec.message().orElseThrow();
    String rawResume = spec == null ? "next" : spec.resumeFrom().orElse("next");
    InterruptResumeMode resumeMode =
        switch (rawResume.strip().toLowerCase(Locale.ROOT)) {
          case "current" -> InterruptResumeMode.CURRENT;
          case "next" -> InterruptResumeMode.NEXT;
          default ->
              throw new IllegalArgumentException("Unsupported interrupt resumeFrom: " + rawResume);
        };
    return new InterruptModule(
        new ModuleName(name),
        message,
        spec == null ? List.of() : spec.instructions(),
        resumeMode,
        spec == null ? 75 : spec.exitCode().orElse(75));
  }

  NerdFontModule nerdFontModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = spec(entry);
    return new NerdFontModule(
        name(entry),
        spec.installerVersion().orElse(KnownTools.NERD_FONTS_INSTALLER.version()),
        spec.nerdfontBinary().orElse(KnownTools.NERD_FONTS_INSTALLER.executableName()),
        nerdFontConfig(spec),
        spec.configIsObject()
            ? Optional.empty()
            : spec.nerdFontsConfigPath().map(path -> Path.of(expandHome(path))),
        spec.probeCommand());
  }

  BinstallerModule binstallerModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new BinstallerModule(
        name(entry),
        Path.of(
            expandHome(
                requireField(
                    spec.dotfilesConfig().orElse(null), support.planName(entry) + ".spec.config"))),
        spec.only(),
        spec.skip(),
        spec.locked(),
        spec.lockFile().map(path -> Path.of(expandHome(path))),
        spec.installerVersion().orElse(KnownTools.BINSTALLER.version()),
        spec.binstallerBinary().orElse(KnownTools.BINSTALLER.executableName()),
        spec.probeCommand(),
        support.continueOnError(entry, policy));
  }

  UserGroupsModule userGroupsModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new UserGroupsModule(
        name(entry),
        spec.user(),
        spec.groups(),
        spec.createMissing(),
        spec.logoutCheckpoint(),
        spec.message(),
        support.continueOnError(entry, policy));
  }

  GitConfigModule gitConfigModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new GitConfigModule(
        name(entry),
        enumValue(GitConfigScope.class, spec.scope().orElse("global")),
        spec.entries(),
        support.continueOnError(entry, policy));
  }

  GitRepoModule gitRepoModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    var repos =
        spec.repos().stream()
            .map(
                repo ->
                    new GitRepoModule.GitRepo(
                        requireField(repo.url, support.planName(entry) + ".spec.repos[].url"),
                        requireField(repo.dest, support.planName(entry) + ".spec.repos[].dest"),
                        Optional.ofNullable(repo.ref),
                        Optional.ofNullable(repo.depth),
                        repo.submodules,
                        enumValue(GitRepoUpdate.class, repo.update)))
            .toList();
    return new GitRepoModule(name(entry), repos, support.continueOnError(entry, policy));
  }

  SystemdUnitModule systemdUnitModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    var units =
        spec.units().stream()
            .map(
                unit ->
                    new SystemdUnitModule.SystemdUnit(
                        requireField(unit.name, support.planName(entry) + ".spec.units[].name"),
                        unit.enabled,
                        enumValue(SystemdState.class, unit.state),
                        unit.mask))
            .toList();
    return new SystemdUnitModule(
        name(entry),
        enumValue(SystemdScope.class, spec.scope().orElse("system")),
        units,
        support.continueOnError(entry, policy));
  }

  SystemSettingModule systemSettingModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new SystemSettingModule(
        name(entry),
        spec.localRtc(),
        spec.ntp(),
        spec.timezone(),
        spec.hostname(),
        spec.locale(),
        support.continueOnError(entry, policy));
  }

  SystemUpdateModule systemUpdateModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    return new SystemUpdateModule(
        name(entry),
        PackageManagerKind.valueOf(
            requireField(
                    spec.packageManager().orElse(null),
                    support.planName(entry) + ".spec.packageManager")
                .toUpperCase()),
        spec.distUpgrade(),
        spec.refreshOnly(),
        spec.timeout().map(MappingSupport::duration),
        support.continueOnError(entry, policy));
  }

  GpgKeyModule gpgKeyModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    var keys =
        spec.keys().stream()
            .map(
                key ->
                    new GpgKeyModule.GpgKey(
                        requireField(key.url, support.planName(entry) + ".spec.keys[].url"),
                        Optional.ofNullable(key.keyring).map(path -> Path.of(expandHome(path))),
                        requireField(
                            key.fingerprint, support.planName(entry) + ".spec.keys[].fingerprint")))
            .toList();
    return new GpgKeyModule(name(entry), keys, support.continueOnError(entry, policy));
  }

  ToolPackagesModule toolPackagesModule(PlanEntryDocument entry, BootstrapPolicy policy) {
    PlanSpecDocument spec = spec(entry);
    var backend =
        ToolPackageBackend.fromId(
                requireField(
                    spec.backend().orElse(null), support.planName(entry) + ".spec.backend"))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unsupported tool-packages backend: " + spec.backend().orElse("")));
    return new ToolPackagesModule(
        name(entry),
        backend,
        spec.packages().stream().map(MappingSupport::toolPackage).toList(),
        support.continueOnError(entry, policy));
  }

  ZypperRepositoryModule zypperRepositoryModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = spec(entry);
    String name = support.planName(entry);
    return new ZypperRepositoryModule(
        new ModuleName(name),
        spec.repositoryId().orElse(name),
        URI.create(requireField(spec.baseUrl().orElse(null), name + ".spec.baseUrl")),
        Path.of(expandHome(spec.repoFile().orElse("/etc/zypp/repos.d/" + name + ".repo"))),
        spec.gpgKeyUrl().map(URI::create),
        spec.repoEnabled(),
        spec.gpgCheck(),
        spec.autoRefresh(),
        support.sourceSha256(spec.checksum()));
  }

  DotbotModule dotbotModule(PlanEntryDocument entry) {
    PlanSpecDocument spec = spec(entry);
    return new DotbotModule(
        name(entry),
        Path.of(
            expandHome(
                requireField(
                    spec.dotfilesConfig().orElse(null), support.planName(entry) + ".spec.config"))),
        spec.installerVersion().orElse(KnownTools.DOTBOT_GO.version()),
        spec.dotbotBinary().orElse(KnownTools.DOTBOT_GO.executableName()),
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

  private PlanSpecDocument spec(PlanEntryDocument entry) {
    return requireField(entry.spec().orElse(null), support.planName(entry) + ".spec");
  }

  private ModuleName name(PlanEntryDocument entry) {
    return new ModuleName(support.planName(entry));
  }
}
