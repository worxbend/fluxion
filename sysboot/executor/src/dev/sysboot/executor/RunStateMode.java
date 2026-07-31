package dev.sysboot.executor;

/** Defines which persisted decisions may affect the current execution. */
public enum RunStateMode {
  RECORD_ONLY,
  SKIP_RECORDED,
  LIVE_REPROBE;

  public static RunStateMode fromOptions(boolean skipAlreadyInstalled, boolean reProbe) {
    if (reProbe) {
      return LIVE_REPROBE;
    }
    return skipAlreadyInstalled ? SKIP_RECORDED : RECORD_ONLY;
  }

  boolean skipsRecordedWork() {
    return this == SKIP_RECORDED;
  }

  boolean probesInstalledItems() {
    return this != RECORD_ONLY;
  }

  boolean startsFreshState() {
    return this != SKIP_RECORDED;
  }

  boolean validatesStoredManifest() {
    return this != LIVE_REPROBE;
  }
}
