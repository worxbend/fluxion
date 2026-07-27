package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code tool-packages} step. */
public final class ToolPackagesModuleDocument extends ModuleDocument {

  /** cargo-binstall | cargo | snap | pipx | uv-tool | npm-global | go-install */
  @JsonProperty("backend")
  public String backend;

  /** Either bare names or {@code name@version} entries. */
  @JsonProperty("packages")
  public List<String> packages;

  @JsonProperty("continueOnError")
  public boolean continueOnError = true;
}
