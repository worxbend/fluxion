package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class FlatpakModuleDto extends ModuleDto {

  @JsonProperty("remote")
  public String remote = "flathub";

  @JsonProperty("appIds")
  public List<String> appIds;
}
