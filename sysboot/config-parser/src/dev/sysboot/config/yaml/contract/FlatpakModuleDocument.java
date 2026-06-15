package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class FlatpakModuleDocument extends ModuleDocument {

  @JsonProperty("remote")
  public String remote = "flathub";

  @JsonProperty("appIds")
  public List<String> appIds;
}
