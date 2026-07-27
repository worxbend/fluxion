package dev.sysboot.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Description of an external command-line tool that Fluxion delegates work to.
 *
 * <p>Fluxion orchestrates rather than reimplements: dotfiles go to {@code dotbot}, fonts to {@code
 * nerd-fonts-installer}, binary distributions to {@code binstaller}. This record carries everything
 * needed to find such a tool on the host, or to fetch and verify the right release asset for the
 * current platform.
 *
 * @param name binary name, also used as the cache directory name
 * @param repository GitHub {@code owner/repo} publishing the releases
 * @param version release tag to pin, or {@link #LATEST}
 * @param assetTemplates candidate asset filenames, most preferred first, using {@code ${name}},
 *     {@code ${version}}, {@code ${os}} and {@code ${arch}} placeholders. More than one is allowed
 *     because projects rename their assets between releases and a pinned old version must keep
 *     working.
 * @param osNaming how the release spells the operating system in asset names
 * @param checksumPolicy how the release publishes checksums for that asset
 * @param binaryName name of the executable inside the archive, when it differs from {@code name}
 */
public record ToolSpec(
    String name,
    String repository,
    String version,
    List<String> assetTemplates,
    OsNaming osNaming,
    ChecksumPolicy checksumPolicy,
    Optional<String> binaryName) {

  public static final String LATEST = "latest";

  public ToolSpec {
    Objects.requireNonNull(name);
    Objects.requireNonNull(repository);
    Objects.requireNonNull(version);
    Objects.requireNonNull(osNaming);
    Objects.requireNonNull(checksumPolicy);
    assetTemplates = List.copyOf(Objects.requireNonNull(assetTemplates));
    binaryName = binaryName == null ? Optional.empty() : binaryName;
    if (assetTemplates.isEmpty()) {
      throw new IllegalArgumentException("At least one asset template is required");
    }
  }

  public ToolSpec(
      String name,
      String repository,
      String version,
      String assetTemplate,
      OsNaming osNaming,
      ChecksumPolicy checksumPolicy,
      Optional<String> binaryName) {
    this(name, repository, version, List.of(assetTemplate), osNaming, checksumPolicy, binaryName);
  }

  /** How the upstream release publishes checksums for its assets. */
  public enum ChecksumPolicy {
    /** A single {@code checksums.txt} listing every asset. */
    CHECKSUMS_FILE,
    /** A {@code <asset>.sha256} sidecar next to each asset. */
    SIDECAR_SHA256,
    /** No checksums published; the download cannot be verified. */
    NONE
  }

  /** Whether asset names spell macOS the Go way ({@code darwin}) or as {@code macos}. */
  public enum OsNaming {
    GO,
    MACOS;

    String render(HostPlatform platform) {
      return this == GO
          ? platform.operatingSystem().goName()
          : platform.operatingSystem().alternateName();
    }
  }

  public String executableName() {
    return binaryName.orElse(name);
  }

  public boolean isLatest() {
    return LATEST.equals(version);
  }

  public ToolSpec withVersion(String newVersion) {
    return new ToolSpec(
        name, repository, newVersion, assetTemplates, osNaming, checksumPolicy, binaryName);
  }

  public ToolSpec withBinaryName(String executable) {
    return new ToolSpec(
        name,
        repository,
        version,
        assetTemplates,
        osNaming,
        checksumPolicy,
        Optional.of(executable));
  }

  /** Preferred asset name for the given platform. */
  public String assetName(HostPlatform platform) {
    return assetNames(platform).getFirst();
  }

  /** Every candidate asset name for the given platform, most preferred first. */
  public List<String> assetNames(HostPlatform platform) {
    return assetTemplates.stream().map(template -> render(template, platform)).toList();
  }

  /** Base URL of the release that publishes the assets. */
  public String releaseDownloadBase() {
    return "https://github.com/" + repository + "/releases/download/" + version;
  }

  public String assetUrl(HostPlatform platform) {
    return assetUrl(assetName(platform));
  }

  public String assetUrl(String assetName) {
    return releaseDownloadBase() + "/" + assetName;
  }

  private String render(String template, HostPlatform platform) {
    return substitute(
        template,
        Map.of(
            "name",
            name,
            "version",
            version,
            "os",
            osNaming.render(platform),
            "arch",
            platform.architecture().goName()));
  }

  private static String substitute(String template, Map<String, String> values) {
    String result = template;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }
}
