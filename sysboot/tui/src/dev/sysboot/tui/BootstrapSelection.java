package dev.sysboot.tui;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.Phase;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BootstrapSelection {

  private final Set<String> selectedPhases;
  private final Set<String> selectedModules;
  private final Map<String, Set<String>> selectedEntries;

  private BootstrapSelection(
      Set<String> selectedPhases,
      Set<String> selectedModules,
      Map<String, Set<String>> selectedEntries) {
    this.selectedPhases = selectedPhases;
    this.selectedModules = selectedModules;
    this.selectedEntries = selectedEntries;
  }

  static BootstrapSelection allSelected(List<Phase> phases) {
    Set<String> phasesSelected = new LinkedHashSet<>();
    Set<String> modulesSelected = new LinkedHashSet<>();
    Map<String, Set<String>> entriesSelected = new LinkedHashMap<>();
    for (Phase phase : phases) {
      phasesSelected.add(phase.name().value());
      for (BootstrapModule module : phase.modules()) {
        String moduleName = module.name().value();
        modulesSelected.add(moduleName);
        entriesSelected.put(moduleName, new LinkedHashSet<>(SelectionEntryCatalog.entries(module)));
      }
    }
    return new BootstrapSelection(phasesSelected, modulesSelected, entriesSelected);
  }

  boolean phaseSelected(Phase phase) {
    return selectedPhases.contains(phase.name().value());
  }

  boolean moduleSelected(BootstrapModule module) {
    return selectedModules.contains(module.name().value());
  }

  boolean entrySelected(BootstrapModule module, String entry) {
    return selectedEntries.getOrDefault(module.name().value(), Set.of()).contains(entry);
  }

  Set<String> selectedEntries(BootstrapModule module) {
    return Set.copyOf(selectedEntries.getOrDefault(module.name().value(), Set.of()));
  }

  void togglePhase(Phase phase) {
    String phaseName = phase.name().value();
    if (selectedPhases.remove(phaseName)) {
      phase.modules().forEach(this::deselectModule);
    } else {
      selectedPhases.add(phaseName);
      phase.modules().forEach(this::selectModule);
    }
  }

  void toggleModule(BootstrapModule module) {
    if (moduleSelected(module)) {
      deselectModule(module);
    } else {
      selectModule(module);
    }
  }

  void toggleEntry(BootstrapModule module, String entry) {
    Set<String> entries =
        selectedEntries.computeIfAbsent(module.name().value(), ignored -> Set.of());
    Set<String> mutable = new LinkedHashSet<>(entries);
    if (!mutable.remove(entry)) {
      mutable.add(entry);
    }
    selectedEntries.put(module.name().value(), mutable);
    if (mutable.isEmpty()) {
      selectedModules.remove(module.name().value());
    } else {
      selectedModules.add(module.name().value());
    }
  }

  private void selectModule(BootstrapModule module) {
    selectedModules.add(module.name().value());
    selectedEntries.put(
        module.name().value(), new LinkedHashSet<>(SelectionEntryCatalog.entries(module)));
  }

  private void deselectModule(BootstrapModule module) {
    selectedModules.remove(module.name().value());
    selectedEntries.put(module.name().value(), new LinkedHashSet<>());
  }
}
