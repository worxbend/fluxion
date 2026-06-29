package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class SourcesDocument {

  @JsonProperty("entries")
  private List<SourceDocument> entries;

  @JsonProperty("apt")
  private List<SourceDocument> apt;

  @JsonProperty("dnf")
  private List<SourceDocument> dnf;

  @JsonProperty("rpm")
  private List<SourceDocument> rpm;

  @JsonProperty("pacman")
  private List<SourceDocument> pacman;

  @JsonProperty("zypper")
  private List<SourceDocument> zypper;

  @JsonProperty("flatpak")
  private List<SourceDocument> flatpak;

  public List<SourceDocument> entries() {
    return DocumentDefaults.list(entries);
  }

  public List<SourceDocument> apt() {
    return DocumentDefaults.list(apt);
  }

  public List<SourceDocument> dnf() {
    return DocumentDefaults.list(dnf);
  }

  public List<SourceDocument> rpm() {
    return DocumentDefaults.list(rpm);
  }

  public List<SourceDocument> pacman() {
    return DocumentDefaults.list(pacman);
  }

  public List<SourceDocument> zypper() {
    return DocumentDefaults.list(zypper);
  }

  public List<SourceDocument> flatpak() {
    return DocumentDefaults.list(flatpak);
  }
}
