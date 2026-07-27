package dev.sysboot.executor;

import dev.sysboot.core.ShellRunner;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The shell-backed executors bound to one {@link ShellRunner}.
 *
 * <p>A phase with {@code RestartPolicy.RequiresNewShell} runs through a login-shell wrapper rather
 * than the primary runner, so these executors are per-runner rather than per-orchestrator.
 * Instances are cached: previously the orchestrator built a fresh executor for every item and
 * silently ignored the ones injected into its constructor, which made them impossible to stub in
 * tests.
 */
final class PhaseExecutors {

  private final ShellScriptExecutor shellScript;
  private final ShellCommandExecutor shellCommand;
  private final DotbotExecutor dotbot;
  private final DefaultShellExecutor defaultShell;
  private final OhMyZshExecutor ohMyZsh;
  private final ToolchainExecutor toolchain;
  private final NerdFontExecutor nerdFont;
  private final ShellReloadExecutor shellReload;
  private final BinstallerExecutor binstaller;
  private final UserGroupsExecutor userGroups;
  private final GitConfigExecutor gitConfig;
  private final GitRepoExecutor gitRepo;
  private final SystemdUnitExecutor systemdUnit;
  private final SystemSettingExecutor systemSetting;
  private final SystemUpdateExecutor systemUpdate;
  private final GpgKeyExecutor gpgKey;
  private final ToolPackagesExecutor toolPackages;

  private PhaseExecutors(
      ShellScriptExecutor shellScript,
      ShellCommandExecutor shellCommand,
      DotbotExecutor dotbot,
      DefaultShellExecutor defaultShell,
      OhMyZshExecutor ohMyZsh,
      ToolchainExecutor toolchain,
      NerdFontExecutor nerdFont,
      ShellReloadExecutor shellReload,
      BinstallerExecutor binstaller,
      UserGroupsExecutor userGroups,
      GitConfigExecutor gitConfig,
      GitRepoExecutor gitRepo,
      SystemdUnitExecutor systemdUnit,
      SystemSettingExecutor systemSetting,
      SystemUpdateExecutor systemUpdate,
      GpgKeyExecutor gpgKey,
      ToolPackagesExecutor toolPackages) {
    this.shellScript = Objects.requireNonNull(shellScript);
    this.shellCommand = Objects.requireNonNull(shellCommand);
    this.dotbot = Objects.requireNonNull(dotbot);
    this.defaultShell = Objects.requireNonNull(defaultShell);
    this.ohMyZsh = Objects.requireNonNull(ohMyZsh);
    this.toolchain = Objects.requireNonNull(toolchain);
    this.nerdFont = Objects.requireNonNull(nerdFont);
    this.shellReload = Objects.requireNonNull(shellReload);
    this.binstaller = Objects.requireNonNull(binstaller);
    this.userGroups = Objects.requireNonNull(userGroups);
    this.gitConfig = Objects.requireNonNull(gitConfig);
    this.gitRepo = Objects.requireNonNull(gitRepo);
    this.systemdUnit = Objects.requireNonNull(systemdUnit);
    this.systemSetting = Objects.requireNonNull(systemSetting);
    this.systemUpdate = Objects.requireNonNull(systemUpdate);
    this.gpgKey = Objects.requireNonNull(gpgKey);
    this.toolPackages = Objects.requireNonNull(toolPackages);
  }

  static PhaseExecutors forRunner(ShellRunner runner) {
    return new PhaseExecutors(
        new ShellScriptExecutor(runner),
        new ShellCommandExecutor(runner),
        new DotbotExecutor(runner),
        new DefaultShellExecutor(runner),
        new OhMyZshExecutor(runner),
        new ToolchainExecutor(runner),
        new NerdFontExecutor(runner),
        new ShellReloadExecutor(runner),
        new BinstallerExecutor(runner),
        new UserGroupsExecutor(runner),
        new GitConfigExecutor(runner),
        new GitRepoExecutor(runner),
        new SystemdUnitExecutor(runner),
        new SystemSettingExecutor(runner),
        new SystemUpdateExecutor(runner),
        new GpgKeyExecutor(runner),
        new ToolPackagesExecutor(runner));
  }

  /** Uses the collaborators supplied to the orchestrator, so injected stubs take effect. */
  static PhaseExecutors injected(
      ShellRunner runner,
      ShellScriptExecutor shellScript,
      DotbotExecutor dotbot,
      DefaultShellExecutor defaultShell,
      OhMyZshExecutor ohMyZsh,
      ToolchainExecutor toolchain,
      NerdFontExecutor nerdFont,
      ShellReloadExecutor shellReload) {
    return new PhaseExecutors(
        shellScript,
        new ShellCommandExecutor(runner),
        dotbot,
        defaultShell,
        ohMyZsh,
        toolchain,
        nerdFont,
        shellReload,
        new BinstallerExecutor(runner),
        new UserGroupsExecutor(runner),
        new GitConfigExecutor(runner),
        new GitRepoExecutor(runner),
        new SystemdUnitExecutor(runner),
        new SystemSettingExecutor(runner),
        new SystemUpdateExecutor(runner),
        new GpgKeyExecutor(runner),
        new ToolPackagesExecutor(runner));
  }

  ShellScriptExecutor shellScript() {
    return shellScript;
  }

  ShellCommandExecutor shellCommand() {
    return shellCommand;
  }

  DotbotExecutor dotbot() {
    return dotbot;
  }

  DefaultShellExecutor defaultShell() {
    return defaultShell;
  }

  OhMyZshExecutor ohMyZsh() {
    return ohMyZsh;
  }

  ToolchainExecutor toolchain() {
    return toolchain;
  }

  NerdFontExecutor nerdFont() {
    return nerdFont;
  }

  ShellReloadExecutor shellReload() {
    return shellReload;
  }

  BinstallerExecutor binstaller() {
    return binstaller;
  }

  UserGroupsExecutor userGroups() {
    return userGroups;
  }

  GitConfigExecutor gitConfig() {
    return gitConfig;
  }

  GitRepoExecutor gitRepo() {
    return gitRepo;
  }

  SystemdUnitExecutor systemdUnit() {
    return systemdUnit;
  }

  SystemSettingExecutor systemSetting() {
    return systemSetting;
  }

  SystemUpdateExecutor systemUpdate() {
    return systemUpdate;
  }

  GpgKeyExecutor gpgKey() {
    return gpgKey;
  }

  ToolPackagesExecutor toolPackages() {
    return toolPackages;
  }

  /** Per-runner cache keyed by identity, since runners are wrappers built per restart policy. */
  static final class Registry {

    private final Map<ShellRunner, PhaseExecutors> byRunner = new IdentityHashMap<>();

    Registry(ShellRunner primaryRunner, PhaseExecutors primaryExecutors) {
      byRunner.put(primaryRunner, primaryExecutors);
    }

    synchronized PhaseExecutors forRunner(ShellRunner runner) {
      return byRunner.computeIfAbsent(runner, PhaseExecutors::forRunner);
    }
  }
}
