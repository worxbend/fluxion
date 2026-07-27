package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class PolicyDocument {

  @JsonProperty("dryRun")
  private Boolean dryRun;

  @JsonProperty("continueOnError")
  private Boolean continueOnError;

  @JsonProperty("requireSudo")
  private Boolean requireSudo;

  @JsonProperty("statePath")
  private String statePath;

  public Optional<Boolean> dryRun() {
    return DocumentDefaults.optional(dryRun);
  }

  public Optional<Boolean> continueOnError() {
    return DocumentDefaults.optional(continueOnError);
  }

  public Optional<Boolean> requireSudo() {
    return DocumentDefaults.optional(requireSudo);
  }

  public Optional<String> statePath() {
    return DocumentDefaults.optional(statePath);
  }
}
