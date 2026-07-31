package dev.sysboot.executor;

import dev.sysboot.core.HostPlatform;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates the external tools Fluxion delegates work to.
 *
 * <p>Resolution order is deliberate: a tool the user already installed wins, then a previously
 * cached download, then a fresh verified download. The binstaller delegation boundary is stricter:
 * only Fluxion's verified managed copy may execute, and its cached digest is checked on every
 * resolution.
 *
 * <p>Downloads are checksum-verified whenever the upstream release publishes checksums, matching
 * the guarantee each project's own {@code install.sh} gives. A tool that cannot be verified is
 * refused rather than executed.
 */
public final class ToolBroker {

  private static final Logger log = LoggerFactory.getLogger(ToolBroker.class);
  private static final String SHA_256 = "SHA-256";
  private static final ConcurrentHashMap<Path, ReentrantLock> JVM_INSTALL_LOCKS =
      new ConcurrentHashMap<>();

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
    if (!requiresManagedCopy(spec)) {
      Optional<Path> onPath = pathLookup.find(spec.executableName());
      if (onPath.isPresent()) {
        log.debug("Using {} already on PATH at {}", spec.name(), onPath.orElseThrow());
        return onPath.orElseThrow();
      }
    }
    Path cached = cache.executable(spec);
    if (isUsable(spec, cached)) {
      log.debug("Using cached {} {} at {}", spec.name(), spec.version(), cached);
      return cached;
    }
    return installLocked(spec, cached);
  }

  /** Reports where a tool would come from, without installing anything. */
  public Resolution describe(ToolSpec spec) {
    if (!requiresManagedCopy(spec)) {
      Optional<Path> onPath = pathLookup.find(spec.executableName());
      if (onPath.isPresent()) {
        return new Resolution(spec, Source.PATH, onPath.orElseThrow());
      }
    }
    Path cached = cache.executable(spec);
    if (isUsable(spec, cached)) {
      return new Resolution(spec, Source.CACHE, cached);
    }
    return new Resolution(spec, Source.DOWNLOAD, cached);
  }

  private Path install(ToolSpec spec, Path destination) {
    destination = cache.requireConfined(destination);
    Path archive = null;
    List<String> candidates = spec.assetNames(platform);
    var failures = new ArrayList<String>();
    try {
      Files.createDirectories(PathRequirements.parent(destination, "Tool destination"));
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

  private Path installLocked(ToolSpec spec, Path destination) {
    destination = cache.requireConfined(destination);
    Path lockPath = cache.installLock(spec);
    ReentrantLock processLock =
        JVM_INSTALL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
    processLock.lock();
    try {
      Path parent = PathRequirements.parent(destination, "Tool destination");
      Files.createDirectories(parent);
      makePrivateDirectory(parent);
      try (FileChannel channel =
          FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
        try (FileLock installLock = channel.lock()) {
          if (!installLock.isValid()) {
            throw new IOException("Unable to acquire tool installation lock");
          }
          makePrivateFile(lockPath);
          return isUsable(spec, destination) ? destination : install(spec, destination);
        }
      }
    } catch (IOException e) {
      throw new ToolResolutionException(
          "Failed to lock installation of " + spec.name() + " " + spec.version(), e);
    } finally {
      processLock.unlock();
    }
  }

  private boolean tryInstall(
      ToolSpec spec, String assetName, Path archive, Path destination, List<String> failures)
      throws IOException {
    Optional<String> trustedDigest = spec.expectedSha256(assetName);
    if (trustedDigest.isEmpty()) {
      failures.add(assetName + " (not present in the trusted release-digest catalog)");
      return false;
    }
    URI assetUrl = URI.create(spec.assetUrl(assetName));
    try {
      log.info("Downloading {} {} from {}", spec.name(), spec.version(), assetUrl);
      downloadClient.downloadToFile(assetUrl, archive);
    } catch (IOException e) {
      failures.add(assetUrl + " (" + e.getMessage() + ")");
      return false;
    }
    verifyChecksum(spec, assetName, archive, trustedDigest.orElseThrow());
    new ToolArchivePublisher().publish(archive, spec, destination);
    publishIntegrityProof(spec, destination);
    return true;
  }

  private boolean requiresManagedCopy(ToolSpec spec) {
    return KnownTools.BINSTALLER.repository().equals(spec.repository())
        && KnownTools.BINSTALLER.name().equals(spec.name());
  }

  private boolean isUsable(ToolSpec spec, Path executable) {
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || !Files.isExecutable(executable)) {
      return false;
    }
    if (!requiresManagedCopy(spec)) {
      return true;
    }
    Path proof = cache.integrityProof(spec);
    try {
      if (!Files.isRegularFile(proof, LinkOption.NOFOLLOW_LINKS) || Files.size(proof) > 128) {
        return false;
      }
      String expected = Files.readString(proof).strip();
      return expected.matches("[0-9a-fA-F]{64}") && expected.equalsIgnoreCase(sha256(executable));
    } catch (IOException e) {
      return false;
    }
  }

  private void publishIntegrityProof(ToolSpec spec, Path executable) throws IOException {
    Path proof = cache.integrityProof(spec);
    Path temporary =
        Files.createTempFile(
            PathRequirements.parent(proof, "Tool integrity proof"),
            "." + proof.getFileName(),
            ".part");
    try {
      Files.writeString(
          temporary,
          sha256(executable) + System.lineSeparator(),
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
      makePrivateFile(temporary);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      moveAtomically(temporary, proof);
    } finally {
      deleteQuietly(temporary);
    }
  }

  private void moveAtomically(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void verifyChecksum(ToolSpec spec, String assetName, Path archive, String trustedDigest)
      throws IOException {
    Optional<String> published = publishedChecksum(spec, assetName);
    if (published.isPresent() && !published.orElseThrow().equalsIgnoreCase(trustedDigest)) {
      throw new ToolResolutionException(
          "Published checksum for %s %s disagrees with the trusted release-digest catalog"
              .formatted(spec.name(), spec.version()));
    }
    String actual = sha256(archive);
    if (!actual.equalsIgnoreCase(trustedDigest)) {
      throw new ToolResolutionException(
          "Checksum mismatch for %s %s: expected %s but downloaded %s"
              .formatted(spec.name(), spec.version(), trustedDigest, actual));
    }
  }

  private Optional<String> publishedChecksum(ToolSpec spec, String assetName) throws IOException {
    return switch (spec.checksumPolicy()) {
      case NONE -> Optional.empty();
      case SIDECAR_SHA256 ->
          Optional.of(
              ChecksumResolver.parseSidecarSha256(
                  downloadClient.downloadText(
                      URI.create(spec.releaseDownloadBase() + "/" + assetName + ".sha256")),
                  assetName));
      case CHECKSUMS_FILE ->
          Optional.of(
              ChecksumResolver.parseChecksumsFileSha256(
                  downloadClient.downloadText(
                      URI.create(spec.releaseDownloadBase() + "/checksums.txt")),
                  assetName));
    };
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

  private void makePrivateDirectory(Path directory) throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
  }

  private void makePrivateFile(Path file) throws IOException {
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
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

  /** Looks an executable up on {@code PATH} without invoking a shell builtin. */
  public interface PathLookup {

    Optional<Path> find(String executable);
  }

  public static final class EnvPathLookup implements PathLookup {

    private final String path;

    public EnvPathLookup() {
      this(System.getenv("PATH"));
    }

    EnvPathLookup(String path) {
      this.path = path;
    }

    @Override
    public Optional<Path> find(String executable) {
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
