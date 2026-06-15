package dev.sysboot.executor;

import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.List;
import java.util.Optional;

public final class ExecutionPlanBuilder {

  private final PackageManagerExecutorRegistry packageManagerRegistry;
  private final ModuleExecutorRegistry moduleExecutorRegistry;
  private final PhaseExecutionPlanner phasePlanner;

  public ExecutionPlanBuilder(PackageManagerExecutorRegistry packageManagerRegistry) {
    this.packageManagerRegistry = packageManagerRegistry;
    this.moduleExecutorRegistry =
        new ModuleExecutorRegistry(List.of(new PackageModuleExecutor(packageManagerRegistry)));
    this.phasePlanner = new PhaseExecutionPlanner();
  }

  public ExecutionPlan build(BootstrapConfig config) {
    List<ExecutionPlan.Phase> phases =
        phasePlanner.plan(config.phases()).stream().map(this::phase).toList();
    return new ExecutionPlan(config.profileName().value(), phases);
  }

  private ExecutionPlan.Phase phase(Phase phase) {
    return new ExecutionPlan.Phase(
        phase.name().value(),
        phase.dependsOn().stream().map(dep -> dep.value()).toList(),
        restartEffect(phase.restartPolicy()),
        phase.modules().stream().map(this::module).toList());
  }

  private ExecutionPlan.Module module(BootstrapModule module) {
    return new ExecutionPlan.Module(
        module.name().value(), moduleType(module), items(module).stream().map(this::item).toList());
  }

  private List<ModuleItem> items(BootstrapModule module) {
    return moduleExecutorRegistry
        .find(module)
        .map(executor -> executor.items(module))
        .orElseGet(() -> fallbackItems(module));
  }

  private ExecutionPlan.Item item(ModuleItem item) {
    Optional<List<String>> commandPreview =
        item.packageManager()
            .map(
                kind ->
                    packageManagerRegistry
                        .forKind(kind)
                        .installCommand(new dev.sysboot.core.PackageName(item.key())));
    return new ExecutionPlan.Item(item, commandPreview);
  }

  private List<ModuleItem> fallbackItems(BootstrapModule module) {
    return switch (module) {
      case FlatpakModule fm ->
          fm.appIds().stream()
              .map(app -> new ModuleItem(fm.name(), app, ItemType.FLATPAK))
              .toList();
      case ShellScriptModule sm ->
          List.of(new ModuleItem(sm.name(), sm.script().toString(), ItemType.SHELL_SCRIPT));
      case CompiledBinaryModule bm ->
          List.of(new ModuleItem(bm.name(), bm.installPath().toString(), ItemType.COMPILED_BINARY));
      case DotbotModule dm ->
          List.of(new ModuleItem(dm.name(), dm.config().toString(), ItemType.DOTBOT));
      case DefaultShellModule dsm ->
          List.of(new ModuleItem(dsm.name(), dsm.shellPath().toString(), ItemType.DEFAULT_SHELL));
      case OhMyZshModule omz ->
          List.of(new ModuleItem(omz.name(), omz.installDir().toString(), ItemType.OH_MY_ZSH));
      case ToolchainModule tm ->
          List.of(new ModuleItem(tm.name(), tm.kind().name().toLowerCase(), ItemType.TOOLCHAIN));
      case NerdFontModule nfm ->
          List.of(new ModuleItem(nfm.name(), "nerd-fonts", ItemType.NERD_FONT));
      case ShellReloadModule srm ->
          List.of(new ModuleItem(srm.name(), srm.shell().binaryName(), ItemType.SHELL_RELOAD));
      case ShellCommandModule sc ->
          List.of(new ModuleItem(sc.name(), sc.name().value(), ItemType.SHELL_COMMAND));
      case AssertModule am ->
          List.of(new ModuleItem(am.name(), am.name().value(), ItemType.ASSERT));
      case ManualModule mm ->
          List.of(new ModuleItem(mm.name(), mm.name().value(), ItemType.MANUAL));
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
    };
  }

  private ExecutionPlan.RestartEffect restartEffect(RestartPolicy policy) {
    return switch (policy) {
      case RestartPolicy.None ignored -> ExecutionPlan.RestartEffect.NONE;
      case RestartPolicy.PromptLogout ignored -> ExecutionPlan.RestartEffect.PROMPT_LOGOUT;
      case RestartPolicy.RequiresNewShell ignored -> ExecutionPlan.RestartEffect.REQUIRES_NEW_SHELL;
    };
  }

  private String moduleType(BootstrapModule module) {
    return switch (module) {
      case PackageModule ignored -> "packages";
      case FlatpakModule ignored -> "flatpak";
      case ShellScriptModule ignored -> "shell-script";
      case CompiledBinaryModule ignored -> "compiled-binary";
      case ZypperModule ignored -> "zypper";
      case DotbotModule ignored -> "dotbot";
      case DefaultShellModule ignored -> "default-shell";
      case OhMyZshModule ignored -> "oh-my-zsh";
      case ToolchainModule ignored -> "toolchain";
      case NerdFontModule ignored -> "nerd-font";
      case ShellReloadModule ignored -> "shell-reload";
      case ShellCommandModule ignored -> "shell-command";
      case AssertModule ignored -> "assert";
      case ManualModule ignored -> "manual";
    };
  }
}
