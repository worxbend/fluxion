package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code gpg-key} step. */
public final class GpgKeyModuleDocument extends ModuleDocument {

  @JsonProperty("keys")
  public List<KeyDocument> keys;

  @JsonProperty("continueOnError")
  public boolean continueOnError;

  /** One signing key. */
  public static final class KeyDocument {

    @JsonProperty("url")
    public String url;

    /** Where a dearmoured key is written. Omit to import into the RPM database instead. */
    @JsonProperty("keyring")
    public String keyring;

    @JsonProperty("fingerprint")
    public String fingerprint;
  }
}
