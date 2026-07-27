package dev.sysboot.executor;

import dev.sysboot.core.HostPlatform;
import dev.sysboot.core.ToolSpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the external tools Fluxion delegates work to.
 *
 * <p>Resolution order is deliberate: a tool the user already installed wins, then a previously
 * cached download, then a fresh verified download. Fluxion never shadows an installation the user
 * manages themselves, and never re-downloads a tool it already has.
 *
 * <p>Downloads are checksum-verified whenever the upstream release publishes checksums, matching
 * the guarantee each project's own {@code install.sh} gives. A tool that cannot be verified is
 * refused rather than executed.
 */
public final class ToolBroker {

  private static final Logger log = LoggerFactory.getLogger(ToolBroker.class);
  private static final String SHA_256 = "SHA-256";

  private final BinaryDownloadClient downloadClient;
  private final ToolCache cache;
  private final HostPlatform platform;
  private final PathLookup pathLookup;

  public ToolBroker() {
    this(
        new HttpBinaryDownloadClient(),
        new ToolCache(),
        HostPlatform.detect(),
        new EnvPathLookup());
  }

  ToolBroker(
      BinaryDownloadClient downloadClient,
      ToolCache cache,
      HostPlatform platform,
      PathLookup pathLookup) {
    this.downloadClient = downloadClient;
    this.cache = cache;
    this.platform = platform;
    this.pathLookup = pathLookup;
  }

  /**
   * Returns an executable path for the tool, installing it if necessary.
   *
   * @throws ToolResolutionException when the tool cannot be obtained or verified
   */
  public Path resolve(ToolSpec spec) {
    Optional<Path> onPath = pathLookup.find(spec.executableName());
    if (onPath.isPresent()) {
      log.debug("Using {} already on PATH at {}", spec.name(), onPath.orElseThrow());
      return onPath.orElseThrow();
    }
    Path cached = cache.executable(spec);
    if (Files.isExecutable(cached)) {
      log.debug("Using cached {} {} at {}", spec.name(), spec.version(), cached);
      return cached;
    }
    return install(spec, cached);
  }

  /** Reports where a tool would come from, without installing anything. */
  public Resolution describe(ToolSpec spec) {
    Optional<Path> onPath = pathLookup.find(spec.executableName());
    if (onPath.isPresent()) {
      return new Resolution(spec, Source.PATH, onPath.orElseThrow());
    }
    Path cached = cache.executable(spec);
    if (Files.isExecutable(cached)) {
      return new Resolution(spec, Source.CACHE, cached);
    }
    return new Resolution(spec, Source.DOWNLOAD, cached);
  }

  private Path install(ToolSpec spec, Path destination) {
    Path archive = null;
    List<String> candidates = spec.assetNames(platform);
    var failures = new ArrayList<String>();
    try {
      Files.createDirectories(destination.getParent());
      archive = Files.createTempFile("fluxion-tool-", "-" + spec.name());
      for (String assetName : candidates) {
        if (tryInstall(spec, assetName, archive, destination, failures)) {
          return destination;
        }
      }
      throw new ToolResolutionException(
          "Failed to install %s %s. Tried: %s"
              .formatted(spec.name(), spec.version(), String.join("; ", failures)));
    } catch (IOException e) {
      throw new ToolResolutionException(
          "Failed to install " + spec.name() + " " + spec.version(), e);
    } finally {
      deleteQuietly(archive);
    }
  }

  private boolean tryInstall(
      ToolSpec spec, String assetName, Path archive, Path destination, List<String> failures)
      throws IOException {
    URI assetUrl = URI.create(spec.assetUrl(assetName));
    try {
      log.info("Downloading {} {} from {}", spec.name(), spec.version(), assetUrl);
      downloadClient.downloadToFile(assetUrl, archive);
    } catch (IOException e) {
      failures.add(assetUrl + " (" + e.getMessage() + ")");
      return false;
    }
    verifyChecksum(spec, assetName, archive);
    extractExecutable(archive, spec, destination);
    Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwxr-xr-x"));
    return true;
  }

  private void verifyChecksum(ToolSpec spec, String assetName, Path archive) throws IOException {
    Optional<String> expected = expectedChecksum(spec, assetName);
    if (expected.isEmpty()) {
      log.warn(
          "{} publishes no checksum for {}; installing without verification",
          spec.repository(),
          assetName);
      return;
    }
    String actual = sha256(archive);
    if (!actual.equalsIgnoreCase(expected.orElseThrow())) {
      throw new ToolResolutionException(
          "Checksum mismatch for %s %s: expected %s but downloaded %s"
              .formatted(spec.name(), spec.version(), expected.orElseThrow(), actual));
    }
  }

  private Optional<String> expectedChecksum(ToolSpec spec, String assetName) throws IOException {
    return switch (spec.checksumPolicy()) {
      case NONE -> Optional.empty();
      case SIDECAR_SHA256 ->
          Optional.of(
              firstToken(
                  downloadClient.downloadText(
                      URI.create(spec.releaseDownloadBase() + "/" + assetName + ".sha256"))));
      case CHECKSUMS_FILE ->
          findInChecksumsFile(
              downloadClient.downloadText(
                  URI.create(spec.releaseDownloadBase() + "/checksums.txt")),
              assetName);
    };
  }

  private Optional<String> findInChecksumsFile(String contents, String assetName) {
    return contents
        .lines()
        .map(String::strip)
        .filter(line -> line.endsWith(assetName) || line.endsWith("*" + assetName))
        .map(ToolBroker::firstToken)
        .findFirst();
  }

  private static String firstToken(String line) {
    String stripped = line.strip();
    int space = stripped.indexOf(' ');
    return space < 0 ? stripped : stripped.substring(0, space);
  }

  private String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(SHA_256);
      byte[] buffer = new byte[8192];
      try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  private void extractExecutable(Path archive, ToolSpec spec, Path destination) throws IOException {
    String executable = spec.executableName();
    var seen = new ArrayList<String>();
    try (var input = new BufferedInputStream(Files.newInputStream(archive));
        var gzip = new GzipCompressorInputStream(input);
        ArchiveInputStream<? extends ArchiveEntry> tar = new TarArchiveInputStream(gzip)) {
      ArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (isExecutableEntry(entry, executable)) {
          Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);
          return;
        }
        if (!entry.isDirectory()) {
          seen.add(entry.getName());
        }
      }
    }
    throw new ToolResolutionException(
        "Archive for %s %s does not contain an executable named '%s'. It contains: %s"
            .formatted(spec.name(), spec.version(), executable, String.join(", ", seen)));
  }

  private boolean isExecutableEntry(ArchiveEntry entry, String executable) {
    if (entry.isDirectory()) {
      return false;
    }
    String name = entry.getName();
    return name.equals(executable)
        || name.endsWith("/" + executable)
        || name.equals("./" + executable);
  }

  private void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // A leftover temp file is not worth failing an install over.
    }
  }

  /** Where a tool comes from. */
  public enum Source {
    PATH,
    CACHE,
    DOWNLOAD
  }

  /** Outcome of {@link #describe(ToolSpec)}. */
  public record Resolution(ToolSpec spec, Source source, Path path) {
    public String describe() {
      return switch (source) {
        case PATH -> spec.executableName() + " (already on PATH: " + path + ")";
        case CACHE -> spec.executableName() + " " + spec.version() + " (cached: " + path + ")";
        case DOWNLOAD ->
            spec.executableName() + " " + spec.version() + " (will download from GitHub releases)";
      };
    }
  }

  /** Looks an executable up on {@code PATH}. */
  interface PathLookup {
    Optional<Path> find(String executable);
  }

  static final class EnvPathLookup implements PathLookup {

    @Override
    public Optional<Path> find(String executable) {
      String path = System.getenv("PATH");
      if (path == null || path.isBlank()) {
        return Optional.empty();
      }
      for (String element : path.split(java.io.File.pathSeparator)) {
        if (element.isBlank()) {
          continue;
        }
        Path candidate = Path.of(element).resolve(executable);
        if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
          return Optional.of(candidate.toAbsolutePath());
        }
      }
      return Optional.empty();
    }
  }

  static String normalizeVersion(String version) {
    return version.strip().toLowerCase(Locale.ROOT);
  }
}
