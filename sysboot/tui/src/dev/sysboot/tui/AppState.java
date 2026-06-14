package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import java.util.List;

public sealed interface AppState
    permits AppState.Dashboard,
        AppState.ModuleList,
        AppState.ProbePhase,
        AppState.Executing,
        AppState.Logs,
        AppState.SudoPrompt,
        AppState.Completed,
        AppState.Error {

  record Dashboard(List<String> availableProfiles, int selectedIndex) implements AppState {
    public Dashboard {
      availableProfiles = List.copyOf(availableProfiles);
    }
  }

  record ModuleList(BootstrapConfig config, List<Boolean> moduleEnabled, int selectedIndex)
      implements AppState {
    public ModuleList {
      moduleEnabled = List.copyOf(moduleEnabled);
    }
  }

  record ProbePhase(BootstrapConfig config, int totalItems, int probedSoFar, String currentItem)
      implements AppState {}

  record Executing(ExecutionScreenState screen, BootstrapConfig config) implements AppState {}

  record Logs(ExecutionScreenState screen) implements AppState {}

  record SudoPrompt(AppState previousState, String prompt, char[] inputBuffer, int cursorPos)
      implements AppState {}

  record Completed(ExecutionScreenState finalScreen) implements AppState {}

  record Error(String message, Throwable cause) implements AppState {}
}
