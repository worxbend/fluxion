package dev.sysboot.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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
 * @param version release tag whose platform assets are present in {@code assetSha256}
 * @param assetTemplates candidate asset filenames, most preferred first, using {@code ${name}},
 *     {@code ${version}}, {@code ${os}} and {@code ${arch}} placeholders. More than one is allowed
 *     because projects rename their assets between releases and a pinned old version must keep
 *     working.
 * @param osNaming how the release spells the operating system in asset names
 * @param checksumPolicy how the release publishes supplemental checksums for that asset
 * @param binaryName name of the executable inside the archive, when it differs from {@code name}
 * @param assetSha256 trusted literal SHA-256 digests keyed by exact release asset filename
 */
public record ToolSpec(
    String name,
    String repository,
    String version,
    List<String> assetTemplates,
    OsNaming osNaming,
    ChecksumPolicy checksumPolicy,
    Optional<String> binaryName,
    Map<String, String> assetSha256) {

  public static final String LATEST = "latest";
  private static final Pattern CACHE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
  private static final Pattern REPOSITORY =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*");

  public ToolSpec {
    Objects.requireNonNull(name);
    Objects.requireNonNull(repository);
    Objects.requireNonNull(version);
    Objects.requireNonNull(osNaming);
    Objects.requireNonNull(checksumPolicy);
    assetTemplates = List.copyOf(Objects.requireNonNull(assetTemplates));
    binaryName = binaryName == null ? Optional.empty() : binaryName;
    assetSha256 = Map.copyOf(Objects.requireNonNull(assetSha256));
    if (assetTemplates.isEmpty()) {
      throw new IllegalArgumentException("At least one asset template is required");
    }
    name = requireCacheSegment(name, "Tool name");
    version = requireCacheSegment(version, "Tool version");
    if (!REPOSITORY.matcher(repository).matches() || repository.contains("..")) {
      throw new IllegalArgumentException("Tool repository must use a safe owner/repository name");
    }
    assetTemplates.forEach(ToolSpec::requireAssetTemplate);
    binaryName = binaryName.map(value -> requireCacheSegment(value, "Tool binary name"));
    assetSha256.forEach(ToolSpec::requireCatalogEntry);
  }

  public ToolSpec(
      String name,
      String repository,
      String version,
      List<String> assetTemplates,
      OsNaming osNaming,
      ChecksumPolicy checksumPolicy,
      Optional<String> binaryName) {
    this(name, repository, version, assetTemplates, osNaming, checksumPolicy, binaryName, Map.of());
  }

  public ToolSpec(
      String name,
      String repository,
      String version,
      String assetTemplate,
      OsNaming osNaming,
      ChecksumPolicy checksumPolicy,
      Optional<String> binaryName) {
    this(
        name,
        repository,
        version,
        List.of(assetTemplate),
        osNaming,
        checksumPolicy,
        binaryName,
        Map.of());
  }

  public ToolSpec(
      String name,
      String repository,
      String version,
      String assetTemplate,
      OsNaming osNaming,
      ChecksumPolicy checksumPolicy,
      Optional<String> binaryName,
      Map<String, String> assetSha256) {
    this(
        name,
        repository,
        version,
        List.of(assetTemplate),
        osNaming,
        checksumPolicy,
        binaryName,
        assetSha256);
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
    String safeVersion = requireCacheSegment(newVersion, "Tool version");
    if (!safeVersion.equals(version)) {
      throw new IllegalArgumentException(
          "Tool version is not present in the trusted release-digest catalog");
    }
    return new ToolSpec(
        name,
        repository,
        safeVersion,
        assetTemplates,
        osNaming,
        checksumPolicy,
        binaryName,
        assetSha256);
  }

  public ToolSpec withBinaryName(String executable) {
    return new ToolSpec(
        name,
        repository,
        version,
        assetTemplates,
        osNaming,
        checksumPolicy,
        Optional.of(executable),
        assetSha256);
  }

  public ToolSpec withAssetSha256(String assetName, String digest) {
    var catalog = new java.util.LinkedHashMap<>(assetSha256);
    catalog.put(assetName, digest);
    return new ToolSpec(
        name, repository, version, assetTemplates, osNaming, checksumPolicy, binaryName, catalog);
  }

  public Optional<String> expectedSha256(String assetName) {
    return Optional.ofNullable(assetSha256.get(assetName));
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
    return render(template, version, platform);
  }

  private String render(String template, String renderedVersion, HostPlatform platform) {
    return substitute(
        template,
        Map.of(
            "name",
            name,
            "version",
            renderedVersion,
            "os",
            osNaming.render(platform),
            "arch",
            platform.architecture().goName()));
  }

  private static String requireCacheSegment(String value, String subject) {
    Objects.requireNonNull(value, subject + " must not be null");
    if (!value.equals(value.strip())
        || !CACHE_SEGMENT.matcher(value).matches()
        || value.equals(".")
        || value.equals("..")
        || value.contains("..")) {
      throw new IllegalArgumentException(
          subject + " must be a single safe cache-path segment without traversal");
    }
    return value;
  }

  private static void requireAssetTemplate(String template) {
    Objects.requireNonNull(template, "Tool asset template must not be null");
    if (template.isBlank()
        || !template.equals(template.strip())
        || template.equals(".")
        || template.equals("..")
        || template.contains("/")
        || template.contains("\\")
        || template.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Tool asset template must be a single safe release filename");
    }
  }

  private static void requireCatalogEntry(String assetName, String digest) {
    requireAssetTemplate(assetName);
    Objects.requireNonNull(digest, "Tool asset SHA-256 must not be null");
    if (!digest.matches("[0-9a-fA-F]{64}")) {
      throw new IllegalArgumentException("Tool asset SHA-256 must be 64 hexadecimal characters");
    }
  }

  private static String substitute(String template, Map<String, String> values) {
    String result = template;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }
}
