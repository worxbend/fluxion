package dev.sysboot.executor;

/** Invalid phase graph that must be reported consistently as a configuration error. */
public class PhasePlanningException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public PhasePlanningException(String message) {
    super(message);
  }
}
