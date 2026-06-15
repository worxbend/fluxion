package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class NerdFontConfigDocument {

  @JsonProperty("release")
  public String release = "latest";

  @JsonProperty("destination")
  public String destination;

  @JsonProperty("refreshFontCache")
  @JsonAlias("refresh_font_cache")
  public boolean refreshFontCache = true;

  @JsonProperty("families")
  public List<String> families;
}
