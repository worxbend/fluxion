package dev.sysboot.executor;

import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;

/** Emits only Logback configuration errors, suppressing native-image metadata warnings. */
public final class ErrorOnlyLogbackStatusListener implements StatusListener {

  @Override
  public void addStatusEvent(Status status) {
    if (status.getLevel() < Status.ERROR) {
      return;
    }
    System.err.println("Logback configuration error: " + status.getMessage());
    if (status.getThrowable() != null) {
      status.getThrowable().printStackTrace(System.err);
    }
  }
}
