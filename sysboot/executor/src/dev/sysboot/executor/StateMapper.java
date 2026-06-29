package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PlanEntryStateEntry;
import dev.sysboot.core.PlanEntryStatus;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.state.record.BootstrapStateRecord;
import dev.sysboot.executor.state.record.PhaseStateEntryRecord;
import dev.sysboot.executor.state.record.PlanEntryStateEntryRecord;
import dev.sysboot.executor.state.record.StateEntryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class StateMapper {

  private static final int SCHEMA_VERSION = 5;

  private StateMapper() {}

  static BootstrapStateRecord toRecord(BootstrapState state) {
    List<StateEntryRecord> entryRecords =
        state.entries().stream().map(StateMapper::entryToRecord).toList();
    List<PhaseStateEntryRecord> phaseEntryRecords =
        state.phaseEntries().stream().map(StateMapper::phaseEntryToRecord).toList();
    List<PlanEntryStateEntryRecord> planEntryRecords =
        state.planEntryEntries().stream().map(StateMapper::planEntryToRecord).toList();
    return new BootstrapStateRecord(
        SCHEMA_VERSION,
        state.profileName(),
        entryRecords,
        phaseEntryRecords,
        planEntryRecords,
        state.nextPlanEntry().orElse(null));
  }

  static BootstrapState fromRecord(BootstrapStateRecord record) {
    List<StateEntry> entries =
        record.entries == null
            ? List.of()
            : record.entries.stream().map(StateMapper::entryFromRecord).toList();
    List<PhaseStateEntry> phaseEntries =
        record.phaseEntries == null
            ? List.of()
            : record.phaseEntries.stream().map(StateMapper::phaseEntryFromRecord).toList();
    List<PlanEntryStateEntry> planEntries =
        record.planEntryEntries == null
            ? List.of()
            : record.planEntryEntries.stream().map(StateMapper::planEntryFromRecord).toList();
    return new BootstrapState(
        record.profileName,
        Instant.now(),
        "unknown",
        entries,
        phaseEntries,
        planEntries,
        Optional.ofNullable(record.nextPlanEntry));
  }

  private static StateEntryRecord entryToRecord(StateEntry e) {
    return new StateEntryRecord(
        e.profileName(),
        e.moduleName(),
        e.itemKey(),
        e.itemType().name(),
        e.completedAt().toString(),
        e.version().orElse(null),
        e.checksum().orElse(null),
        e.sourceUrl().orElse(null));
  }

  private static StateEntry entryFromRecord(StateEntryRecord record) {
    Instant completedAt =
        record.completedAt != null ? Instant.parse(record.completedAt) : Instant.EPOCH;
    return new StateEntry(
        record.profileName,
        record.moduleName,
        record.itemKey,
        ItemType.valueOf(record.itemType),
        completedAt,
        Optional.ofNullable(record.version),
        Optional.ofNullable(record.checksum),
        Optional.ofNullable(record.sourceUrl));
  }

  private static PhaseStateEntryRecord phaseEntryToRecord(PhaseStateEntry e) {
    return new PhaseStateEntryRecord(
        e.phaseName(),
        e.status().name(),
        e.completedAt().toString(),
        e.fingerprint().orElse(null),
        e.reason().orElse(null));
  }

  private static PhaseStateEntry phaseEntryFromRecord(PhaseStateEntryRecord record) {
    Instant completedAt =
        record.completedAt != null ? Instant.parse(record.completedAt) : Instant.EPOCH;
    return new PhaseStateEntry(
        record.phaseName,
        PhaseStatus.valueOf(record.status),
        completedAt,
        Optional.ofNullable(record.fingerprint),
        Optional.ofNullable(record.reason));
  }

  private static PlanEntryStateEntryRecord planEntryToRecord(PlanEntryStateEntry e) {
    return new PlanEntryStateEntryRecord(
        e.entryName(), e.status().name(), e.updatedAt().toString(), e.message().orElse(null));
  }

  private static PlanEntryStateEntry planEntryFromRecord(PlanEntryStateEntryRecord record) {
    Instant updatedAt = record.updatedAt != null ? Instant.parse(record.updatedAt) : Instant.EPOCH;
    return new PlanEntryStateEntry(
        record.entryName,
        PlanEntryStatus.valueOf(record.status),
        updatedAt,
        Optional.ofNullable(record.message));
  }
}
