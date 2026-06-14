package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.dto.BootstrapStateDto;
import dev.sysboot.executor.dto.PhaseStateEntryDto;
import dev.sysboot.executor.dto.StateEntryDto;
import java.time.Instant;
import java.util.List;

final class StateMapper {

  private static final int SCHEMA_VERSION = 2;

  private StateMapper() {}

  static BootstrapStateDto toDto(BootstrapState state) {
    List<StateEntryDto> entryDtos = state.entries().stream().map(StateMapper::entryToDto).toList();
    List<PhaseStateEntryDto> phaseEntryDtos =
        state.phaseEntries().stream().map(StateMapper::phaseEntryToDto).toList();
    return new BootstrapStateDto(SCHEMA_VERSION, state.profileName(), entryDtos, phaseEntryDtos);
  }

  static BootstrapState fromDto(BootstrapStateDto dto) {
    List<StateEntry> entries =
        dto.entries == null
            ? List.of()
            : dto.entries.stream().map(StateMapper::entryFromDto).toList();
    List<PhaseStateEntry> phaseEntries =
        dto.phaseEntries == null
            ? List.of()
            : dto.phaseEntries.stream().map(StateMapper::phaseEntryFromDto).toList();
    return new BootstrapState(dto.profileName, Instant.now(), "unknown", entries, phaseEntries);
  }

  private static StateEntryDto entryToDto(StateEntry e) {
    return new StateEntryDto(
        e.profileName(),
        e.moduleName(),
        e.itemKey(),
        e.itemType().name(),
        e.completedAt().toString(),
        e.version(),
        e.checksum());
  }

  private static StateEntry entryFromDto(StateEntryDto dto) {
    Instant completedAt = dto.completedAt != null ? Instant.parse(dto.completedAt) : Instant.EPOCH;
    return new StateEntry(
        dto.profileName,
        dto.moduleName,
        dto.itemKey,
        ItemType.valueOf(dto.itemType),
        completedAt,
        dto.version,
        dto.checksum);
  }

  private static PhaseStateEntryDto phaseEntryToDto(PhaseStateEntry e) {
    return new PhaseStateEntryDto(e.phaseName(), e.status().name(), e.completedAt().toString());
  }

  private static PhaseStateEntry phaseEntryFromDto(PhaseStateEntryDto dto) {
    Instant completedAt = dto.completedAt != null ? Instant.parse(dto.completedAt) : Instant.EPOCH;
    return new PhaseStateEntry(dto.phaseName, PhaseStatus.valueOf(dto.status), completedAt);
  }
}
