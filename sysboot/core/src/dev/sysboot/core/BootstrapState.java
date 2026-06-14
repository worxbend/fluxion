package dev.sysboot.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public record BootstrapState(
    String profileName,
    Instant lastRunAt,
    String sysbootVersion,
    List<StateEntry> entries,
    List<PhaseStateEntry> phaseEntries) {

  public BootstrapState {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(lastRunAt);
    Objects.requireNonNull(sysbootVersion);
    Objects.requireNonNull(entries);
    Objects.requireNonNull(phaseEntries);
    entries = List.copyOf(entries);
    phaseEntries = List.copyOf(phaseEntries);
  }

  public BootstrapState(
      String profileName, Instant lastRunAt, String sysbootVersion, List<StateEntry> entries) {
    this(profileName, lastRunAt, sysbootVersion, entries, List.of());
  }

  public Optional<StateEntry> findEntry(String itemKey, ItemType type) {
    return entries.stream()
        .filter(e -> e.itemKey().equals(itemKey) && e.itemType() == type)
        .findFirst();
  }

  public Optional<PhaseStateEntry> findPhaseEntry(String phaseName) {
    return phaseEntries.stream().filter(e -> e.phaseName().equals(phaseName)).findFirst();
  }

  public boolean isPhaseCompleted(String phaseName) {
    return findPhaseEntry(phaseName).map(e -> e.status() == PhaseStatus.COMPLETED).orElse(false);
  }

  public BootstrapState withEntry(StateEntry newEntry) {
    List<StateEntry> updated =
        entries.stream()
            .filter(
                e ->
                    !(e.itemKey().equals(newEntry.itemKey())
                        && e.itemType() == newEntry.itemType()))
            .collect(Collectors.toCollection(ArrayList::new));
    updated.add(newEntry);
    return new BootstrapState(
        profileName, Instant.now(), sysbootVersion, List.copyOf(updated), phaseEntries);
  }

  public BootstrapState withPhaseEntry(PhaseStateEntry newEntry) {
    List<PhaseStateEntry> updated =
        phaseEntries.stream()
            .filter(e -> !e.phaseName().equals(newEntry.phaseName()))
            .collect(Collectors.toCollection(ArrayList::new));
    updated.add(newEntry);
    return new BootstrapState(
        profileName, Instant.now(), sysbootVersion, entries, List.copyOf(updated));
  }

  public static BootstrapState empty(String profileName, String version) {
    return new BootstrapState(profileName, Instant.now(), version, List.of(), List.of());
  }
}
