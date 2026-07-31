package dev.sysboot.executor;

import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.ToolchainModule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Table of the step kinds that are just "run one item and report it".
 *
 * <p>Most kinds share exactly that shape, and each one used to cost two hand-written switch arms:
 * one in the execute switch and a mirrored one in dry-run. Adding step kinds grew {@code
 * BootstrapOrchestratorImpl} past a thousand lines and sixty arms. A lookup table replaces both
 * arms with one row, so a new kind is a line here rather than an edit in two switches that can
 * silently diverge.
 *
 * <p>Kinds with genuinely different shapes stay in the orchestrator: multi-item loops (packages,
 * Flatpak apps, file writes), items with bespoke skip keys (shell scripts), items that record extra
 * state (compiled binaries), and control flow (interrupt, assert, manual, user groups).
 */
final class StepBinding {

  private final Class<? extends BootstrapModule> moduleType;
  private final ItemType itemType;
  private final Function<BootstrapModule, String> itemKey;
  private final BiFunction<BootstrapModule, PhaseExecutors, StepResult> execute;
  private final BiFunction<BootstrapModule, PhaseExecutors, List<String>> preview;

  private StepBinding(
      Class<? extends BootstrapModule> moduleType,
      ItemType itemType,
      Function<BootstrapModule, String> itemKey,
      BiFunction<BootstrapModule, PhaseExecutors, StepResult> execute,
      BiFunction<BootstrapModule, PhaseExecutors, List<String>> preview) {
    this.moduleType = moduleType;
    this.itemType = itemType;
    this.itemKey = itemKey;
    this.execute = execute;
    this.preview = preview;
  }

  ModuleItem item(BootstrapModule module) {
    if (module instanceof DotbotModule) {
      return ModuleItem.configuredModuleItem(module, itemKey(module), itemKey(module), itemType);
    }
    return new ModuleItem(module.name(), itemKey(module), itemType);
  }

  String itemKey(BootstrapModule module) {
    return itemKey.apply(module);
  }

  StepResult execute(BootstrapModule module, PhaseExecutors executors) {
    return execute.apply(module, executors);
  }

  List<String> commandPreview(BootstrapModule module, PhaseExecutors executors) {
    return preview.apply(module, executors);
  }

  /** Casts are confined here, so every row below stays fully typed. */
  private static <M extends BootstrapModule> StepBinding bind(
      Class<M> type,
      ItemType itemType,
      Function<M, String> itemKey,
      BiFunction<M, PhaseExecutors, StepResult> execute,
      BiFunction<M, PhaseExecutors, List<String>> preview) {
    return new StepBinding(
        type,
        itemType,
        module -> itemKey.apply(type.cast(module)),
        (module, executors) -> execute.apply(type.cast(module), executors),
        (module, executors) -> preview.apply(type.cast(module), executors));
  }

  private static final Map<Class<? extends BootstrapModule>, StepBinding> BINDINGS =
      List.of(
              bind(
                  DotbotModule.class,
                  ItemType.DOTBOT,
                  module -> module.config().toString(),
                  (module, executors) -> executors.dotbot().execute(module),
                  (module, executors) -> executors.dotbot().commandPreview(module)),
              bind(
                  DefaultShellModule.class,
                  ItemType.DEFAULT_SHELL,
                  module -> module.shellPath().toString(),
                  (module, executors) -> executors.defaultShell().execute(module),
                  (module, executors) -> executors.defaultShell().commandPreview(module)),
              bind(
                  OhMyZshModule.class,
                  ItemType.OH_MY_ZSH,
                  module -> module.installDir().toString(),
                  (module, executors) -> executors.ohMyZsh().execute(module),
                  (module, executors) -> List.of("sh", "<omz-installer>")),
              bind(
                  ToolchainModule.class,
                  ItemType.TOOLCHAIN,
                  module -> module.kind().name().toLowerCase(),
                  (module, executors) -> executors.toolchain().execute(module),
                  (module, executors) -> List.of("sh", "<installer>")),
              bind(
                  NerdFontModule.class,
                  ItemType.NERD_FONT,
                  module ->
                      module.config().families().isEmpty()
                          ? module.name().value()
                          : module.config().families().getFirst(),
                  (module, executors) -> executors.nerdFont().execute(module),
                  (module, executors) -> executors.nerdFont().commandPreview(module)),
              bind(
                  ShellReloadModule.class,
                  ItemType.SHELL_RELOAD,
                  module -> module.shell().binaryName(),
                  (module, executors) -> executors.shellReload().execute(module),
                  (module, executors) ->
                      List.of(module.shell().binaryName(), "--login", "-i", "-c", "exit")),
              bind(
                  BinstallerModule.class,
                  ItemType.BINSTALLER_PROFILE,
                  BinstallerModule::itemKey,
                  (module, executors) -> executors.binstaller().execute(module),
                  (module, executors) -> executors.binstaller().commandPreview(module)),
              bind(
                  SystemUpdateModule.class,
                  ItemType.SYSTEM_UPDATE,
                  SystemUpdateModule::itemKey,
                  (module, executors) -> executors.systemUpdate().execute(module),
                  (module, executors) -> executors.systemUpdate().commandPreview(module)))
          .stream()
          .collect(Collectors.toUnmodifiableMap(binding -> binding.moduleType, binding -> binding));

  static Optional<StepBinding> find(BootstrapModule module) {
    return Optional.ofNullable(BINDINGS.get(module.getClass()));
  }
}
