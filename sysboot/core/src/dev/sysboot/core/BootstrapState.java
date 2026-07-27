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
    List<PhaseStateEntry> phaseEntries,
    List<PlanEntryStateEntry> planEntryEntries,
    Optional<String> nextPlanEntry,
    Optional<String> manifestIdentity,
    Optional<String> manifestFingerprint) {

  public BootstrapState {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(lastRunAt);
    Objects.requireNonNull(sysbootVersion);
    Objects.requireNonNull(entries);
    Objects.requireNonNull(phaseEntries);
    Objects.requireNonNull(planEntryEntries);
    entries = List.copyOf(entries);
    phaseEntries = List.copyOf(phaseEntries);
    planEntryEntries = List.copyOf(planEntryEntries);
    nextPlanEntry = nextPlanEntry == null ? Optional.empty() : nextPlanEntry;
    manifestIdentity = manifestIdentity == null ? Optional.empty() : manifestIdentity;
    manifestFingerprint = manifestFingerprint == null ? Optional.empty() : manifestFingerprint;
  }

  public BootstrapState(
      String profileName, Instant lastRunAt, String sysbootVersion, List<StateEntry> entries) {
    this(profileName, lastRunAt, sysbootVersion, entries, List.of());
  }

  public BootstrapState(
      String profileName,
      Instant lastRunAt,
      String sysbootVersion,
      List<StateEntry> entries,
      List<PhaseStateEntry> phaseEntries) {
    this(
        profileName,
        lastRunAt,
        sysbootVersion,
        entries,
        phaseEntries,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
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

  public boolean isPhaseCompleted(String phaseName, String fingerprint) {
    Objects.requireNonNull(fingerprint);
    return findPhaseEntry(phaseName)
        .filter(e -> e.status() == PhaseStatus.COMPLETED)
        .flatMap(PhaseStateEntry::fingerprint)
        .filter(fingerprint::equals)
        .isPresent();
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
    return copy(List.copyOf(updated), phaseEntries, planEntryEntries, nextPlanEntry);
  }

  public BootstrapState withPhaseEntry(PhaseStateEntry newEntry) {
    List<PhaseStateEntry> updated =
        phaseEntries.stream()
            .filter(e -> !e.phaseName().equals(newEntry.phaseName()))
            .collect(Collectors.toCollection(ArrayList::new));
    updated.add(newEntry);
    return copy(entries, List.copyOf(updated), planEntryEntries, nextPlanEntry);
  }

  public BootstrapState withPlanEntry(PlanEntryStateEntry newEntry) {
    List<PlanEntryStateEntry> updated =
        planEntryEntries.stream()
            .filter(e -> !e.entryName().equals(newEntry.entryName()))
            .collect(Collectors.toCollection(ArrayList::new));
    updated.add(newEntry);
    return copy(entries, phaseEntries, updated, nextPlanEntry);
  }

  public BootstrapState withNextPlanEntry(Optional<String> nextEntry) {
    return copy(entries, phaseEntries, planEntryEntries, nextEntry);
  }

  public BootstrapState withoutNextPlanEntry() {
    return withNextPlanEntry(Optional.empty());
  }

  public BootstrapState withoutItem(String itemKey) {
    Objects.requireNonNull(itemKey);
    List<StateEntry> updated =
        entries.stream()
            .filter(e -> !e.itemKey().equals(itemKey))
            .collect(Collectors.toCollection(ArrayList::new));
    return copy(List.copyOf(updated), phaseEntries, planEntryEntries, nextPlanEntry);
  }

  public BootstrapState withoutPhase(String phaseName) {
    Objects.requireNonNull(phaseName);
    List<PhaseStateEntry> updated =
        phaseEntries.stream()
            .filter(e -> !e.phaseName().equals(phaseName))
            .collect(Collectors.toCollection(ArrayList::new));
    return copy(entries, List.copyOf(updated), planEntryEntries, nextPlanEntry);
  }

  public BootstrapState withManifestMetadata(String identity, String fingerprint) {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(fingerprint);
    return copy(
        entries,
        phaseEntries,
        planEntryEntries,
        nextPlanEntry,
        Optional.of(identity),
        Optional.of(fingerprint));
  }

  public boolean hasRecordedWork() {
    return !entries.isEmpty()
        || !phaseEntries.isEmpty()
        || !planEntryEntries.isEmpty()
        || nextPlanEntry.isPresent();
  }

  public static BootstrapState empty(String profileName, String version) {
    return new BootstrapState(profileName, Instant.now(), version, List.of());
  }

  private BootstrapState copy(
      List<StateEntry> entries,
      List<PhaseStateEntry> phaseEntries,
      List<PlanEntryStateEntry> planEntryEntries,
      Optional<String> nextPlanEntry) {
    return copy(
        entries,
        phaseEntries,
        planEntryEntries,
        nextPlanEntry,
        manifestIdentity,
        manifestFingerprint);
  }

  private BootstrapState copy(
      List<StateEntry> entries,
      List<PhaseStateEntry> phaseEntries,
      List<PlanEntryStateEntry> planEntryEntries,
      Optional<String> nextPlanEntry,
      Optional<String> manifestIdentity,
      Optional<String> manifestFingerprint) {
    return new BootstrapState(
        profileName,
        Instant.now(),
        sysbootVersion,
        entries,
        phaseEntries,
        planEntryEntries,
        nextPlanEntry,
        manifestIdentity,
        manifestFingerprint);
  }
}
