package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.state.record.BootstrapStateRecord;
import dev.sysboot.executor.state.record.PhaseStateEntryRecord;
import dev.sysboot.executor.state.record.StateEntryRecord;
import java.time.Instant;
import java.util.List;

final class StateMapper {

  private static final int SCHEMA_VERSION = 2;

  private StateMapper() {}

  static BootstrapStateRecord toRecord(BootstrapState state) {
    List<StateEntryRecord> entryRecords =
        state.entries().stream().map(StateMapper::entryToRecord).toList();
    List<PhaseStateEntryRecord> phaseEntryRecords =
        state.phaseEntries().stream().map(StateMapper::phaseEntryToRecord).toList();
    return new BootstrapStateRecord(
        SCHEMA_VERSION, state.profileName(), entryRecords, phaseEntryRecords);
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
    return new BootstrapState(record.profileName, Instant.now(), "unknown", entries, phaseEntries);
  }

  private static StateEntryRecord entryToRecord(StateEntry e) {
    return new StateEntryRecord(
        e.profileName(),
        e.moduleName(),
        e.itemKey(),
        e.itemType().name(),
        e.completedAt().toString(),
        e.version(),
        e.checksum());
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
        record.version,
        record.checksum);
  }

  private static PhaseStateEntryRecord phaseEntryToRecord(PhaseStateEntry e) {
    return new PhaseStateEntryRecord(e.phaseName(), e.status().name(), e.completedAt().toString());
  }

  private static PhaseStateEntry phaseEntryFromRecord(PhaseStateEntryRecord record) {
    Instant completedAt =
        record.completedAt != null ? Instant.parse(record.completedAt) : Instant.EPOCH;
    return new PhaseStateEntry(record.phaseName, PhaseStatus.valueOf(record.status), completedAt);
  }
}
