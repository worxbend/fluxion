package dev.sysboot.core;

public final class FluxionVersion {

  public static final String CURRENT = "1.0.3";

  private FluxionVersion() {}

  public static String current() {
    String override = System.getProperty("fluxion.version", "").strip();
    return override.isBlank() ? CURRENT : override;
  }
}
