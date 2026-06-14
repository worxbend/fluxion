package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;

public sealed interface InstallationStatus
    permits InstallationStatus.NotInstalled,
        InstallationStatus.InstalledFromState,
        InstallationStatus.InstalledByProbe,
        InstallationStatus.Unknown {

  String item();

  /** State file records a prior successful installation by sysboot. */
  record InstalledFromState(String item, Instant installedAt, String version)
      implements InstallationStatus {
    public InstalledFromState {
      Objects.requireNonNull(item);
      Objects.requireNonNull(installedAt);
    }
  }

  /** Live OS probe confirmed the item is present right now. */
  record InstalledByProbe(String item, String detectedVersion) implements InstallationStatus {
    public InstalledByProbe {
      Objects.requireNonNull(item);
    }
  }

  /** Neither state file nor probe can confirm presence — treat as not installed. */
  record NotInstalled(String item) implements InstallationStatus {
    public NotInstalled {
      Objects.requireNonNull(item);
    }
  }

  /** Probe command itself errored — treat conservatively as not installed. */
  record Unknown(String item, String reason) implements InstallationStatus {
    public Unknown {
      Objects.requireNonNull(item);
      Objects.requireNonNull(reason);
    }
  }
}
