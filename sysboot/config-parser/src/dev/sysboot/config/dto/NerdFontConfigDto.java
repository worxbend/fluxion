package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class NerdFontConfigDto {

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
