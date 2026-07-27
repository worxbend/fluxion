package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Optional;

public final class ExecutionDocument {

  @JsonProperty("continueOnError")
  private Boolean continueOnError;

  @JsonProperty("requireSudo")
  private Boolean requireSudo;

  @JsonProperty("parallelism")
  private Integer parallelism;

  @JsonProperty("timeoutSeconds")
  private Integer timeoutSeconds;

  @JsonProperty("shell")
  private String shell;

  @JsonProperty("workingDir")
  private String workingDir;

  @JsonProperty("env")
  private Map<String, String> env;

  public Optional<Boolean> continueOnError() {
    return DocumentDefaults.optional(continueOnError);
  }

  public Optional<Boolean> requireSudo() {
    return DocumentDefaults.optional(requireSudo);
  }

  public Optional<Integer> parallelism() {
    return DocumentDefaults.optional(parallelism);
  }

  public Optional<Integer> timeoutSeconds() {
    return DocumentDefaults.optional(timeoutSeconds);
  }

  public Optional<String> shell() {
    return DocumentDefaults.optional(shell);
  }

  public Optional<String> workingDir() {
    return DocumentDefaults.optional(workingDir);
  }

  public Map<String, String> env() {
    return DocumentDefaults.map(env);
  }
}
