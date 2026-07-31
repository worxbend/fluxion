package dev.sysboot.executor;

import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.SecretRedactor;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Imports repository signing keys only after their declared primary fingerprint is verified. */
public final class GpgKeyExecutor {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final Pattern URL_PARAMETERS =
      Pattern.compile("(?i)((?:https?|file)://[^\\s?#]+)[?#][^\\s]*");
  static final long MAX_KEY_BYTES = 16L * 1024L * 1024L;

  private final ShellRunner shellRunner;
  private final BinaryDownloadClient downloadClient;
  private final Path tempDirectory;
  private final long maxKeyBytes;
  private final UnaryOperator<Path> existingKeyringPath;
  private final PrivilegedArtifactPublisher publisher;
  private final SecretRedactor redactor;

  public GpgKeyExecutor(ShellRunner shellRunner) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(MAX_KEY_BYTES),
        Path.of(System.getProperty("java.io.tmpdir")),
        MAX_KEY_BYTES);
  }

  GpgKeyExecutor(ShellRunner shellRunner, BinaryDownloadClient downloadClient, Path tempDirectory) {
    this(shellRunner, downloadClient, tempDirectory, MAX_KEY_BYTES);
  }

  GpgKeyExecutor(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      Path tempDirectory,
      long maxKeyBytes) {
    this(shellRunner, downloadClient, tempDirectory, maxKeyBytes, UnaryOperator.identity());
  }

  GpgKeyExecutor(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      Path tempDirectory,
      long maxKeyBytes,
      UnaryOperator<Path> existingKeyringPath) {
    this(
        shellRunner,
        downloadClient,
        tempDirectory,
        maxKeyBytes,
        existingKeyringPath,
        new PrivilegedAtomicFilePublisher(shellRunner));
  }

  GpgKeyExecutor(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      Path tempDirectory,
      long maxKeyBytes,
      UnaryOperator<Path> existingKeyringPath,
      PrivilegedArtifactPublisher publisher) {
    this.shellRunner = Objects.requireNonNull(shellRunner);
    this.downloadClient = Objects.requireNonNull(downloadClient);
    this.tempDirectory = Objects.requireNonNull(tempDirectory);
    if (maxKeyBytes <= 0) {
      throw new IllegalArgumentException("maxKeyBytes must be positive");
    }
    this.maxKeyBytes = maxKeyBytes;
    this.existingKeyringPath = Objects.requireNonNull(existingKeyringPath);
    this.publisher = Objects.requireNonNull(publisher);
    this.redactor = new SecretRedactor();
  }

  public StepResult execute(GpgKeyModule module) {
    var failures = new ArrayList<String>();
    for (GpgKeyModule.GpgKey key : module.keys()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      StepResult result = executeItem(key);
      if (result instanceof StepResult.Failure failure) {
        failures.add(failure.errorMessage());
        if (!module.continueOnError()) {
          break;
        }
      }
    }
    return failures.isEmpty()
        ? new StepResult.Success(module.name().value(), Duration.ZERO)
        : new StepResult.Failure(
            module.name().value(), String.join("; ", failures), 1, Duration.ZERO);
  }

  public StepResult executeItem(GpgKeyModule.GpgKey key) {
    return importKey(key)
        .<StepResult>map(
            failure -> new StepResult.Failure(key.itemKey(), failure, 1, Duration.ZERO))
        .orElseGet(() -> new StepResult.Success(key.itemKey(), Duration.ZERO));
  }

  /** Returns true only when a regular, non-symlink keyring matches its declared fingerprint. */
  public boolean alreadyImported(GpgKeyModule.GpgKey key) {
    if (key.keyring().isEmpty()) {
      return false;
    }
    try {
      return verifyExistingKeyring(key, key.keyring().orElseThrow()).isEmpty();
    } catch (NoSuchFileException e) {
      return false;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  public List<String> commandPreview(GpgKeyModule module) {
    var preview = new ArrayList<String>();
    module.keys().forEach(key -> preview.addAll(commandPreview(key)));
    return List.copyOf(preview);
  }

  public List<String> commandPreview(GpgKeyModule.GpgKey key) {
    return List.of(
        "download " + key.publicUrl(),
        "verify OpenPGP fingerprint " + key.fingerprint(),
        key.keyring()
            .map(path -> "install verified keyring " + path)
            .orElse("sudo rpm --import <verified-key>"));
  }

  private Optional<String> importKey(GpgKeyModule.GpgKey key) {
    if (key.keyring().isEmpty()) {
      return downloadVerifyAndImport(key);
    }
    try {
      return verifyExistingKeyring(key, key.keyring().orElseThrow());
    } catch (NoSuchFileException e) {
      return downloadVerifyAndImport(key);
    } catch (IOException | RuntimeException e) {
      return Optional.of(
          "inspect existing keyring for " + key.displayName() + ": " + failureMessage(e, key));
    }
  }

  private Optional<String> verifyExistingKeyring(GpgKeyModule.GpgKey key, Path keyring)
      throws IOException {
    Path staged = Files.createTempFile(tempDirectory, "sysboot-gpg-key-existing-", ".gpg");
    try {
      copyRegularFile(existingKeyringPath.apply(keyring), staged);
      return verifyFingerprint(key, staged, "existing keyring");
    } finally {
      deleteQuietly(staged);
    }
  }

  private Optional<String> downloadVerifyAndImport(GpgKeyModule.GpgKey key) {
    Path downloaded = null;
    try {
      downloaded = Files.createTempFile(tempDirectory, "sysboot-gpg-key-", ".asc");
      download(key, downloaded);
      return stageVerifyAndImport(key, downloaded);
    } catch (IOException | RuntimeException e) {
      return Optional.of("download " + key.displayName() + ": " + failureMessage(e, key));
    } finally {
      deleteQuietly(downloaded);
    }
  }

  private void download(GpgKeyModule.GpgKey key, Path destination) throws IOException {
    URI uri = URI.create(key.url());
    if ("file".equalsIgnoreCase(uri.getScheme())) {
      copyRegularFile(Path.of(uri), destination);
      return;
    }
    downloadClient.downloadToFile(uri, destination);
    requireRegularFile(destination);
  }

  private void copyRegularFile(Path source, Path destination) throws IOException {
    requireRegularFile(source);
    try (InputStream input =
            Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        OutputStream output =
            Files.newOutputStream(
                destination, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      byte[] buffer = new byte[8192];
      long copied = 0;
      for (int read; (read = input.read(buffer)) >= 0; ) {
        copied += read;
        if (copied > maxKeyBytes) {
          throw new IOException("OpenPGP key exceeds " + maxKeyBytes + " bytes");
        }
        output.write(buffer, 0, read);
      }
    }
  }

  private void requireRegularFile(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("OpenPGP key source must be a regular non-symlink file");
    }
    if (attributes.size() > maxKeyBytes) {
      throw new IOException("OpenPGP key exceeds " + maxKeyBytes + " bytes");
    }
  }

  private Optional<String> verifyFingerprint(
      GpgKeyModule.GpgKey key, Path keyFile, String sourceDescription) {
    ProcessResult result;
    try {
      result = shellRunner.run(inspectCommand(keyFile), Map.of(), TIMEOUT);
    } catch (RuntimeException e) {
      return Optional.of(
          "inspect "
              + sourceDescription
              + " for "
              + key.displayName()
              + ": "
              + failureMessage(e, key));
    }
    if (!result.isSuccess()) {
      return Optional.of(
          "inspect "
              + sourceDescription
              + " for "
              + key.displayName()
              + ": "
              + safeDetail(StepOutcome.detail(result), key));
    }
    List<String> fingerprints = primaryFingerprints(result.stdout());
    if (fingerprints.equals(List.of(key.fingerprint()))) {
      return Optional.empty();
    }
    return Optional.of(
        "fingerprint mismatch for "
            + key.displayName()
            + ": expected "
            + key.fingerprint()
            + " but found "
            + fingerprints);
  }

  private List<String> primaryFingerprints(String colonOutput) {
    var fingerprints = new ArrayList<String>();
    boolean awaitingPrimaryFingerprint = false;
    for (String line : colonOutput.lines().toList()) {
      String[] fields = line.split(":", -1);
      if ("pub".equals(fields[0])) {
        if (awaitingPrimaryFingerprint) {
          fingerprints.add("");
        }
        awaitingPrimaryFingerprint = true;
      } else if (awaitingPrimaryFingerprint) {
        fingerprints.add("fpr".equals(fields[0]) && fields.length > 9 ? normalize(fields[9]) : "");
        awaitingPrimaryFingerprint = false;
      }
    }
    if (awaitingPrimaryFingerprint) {
      fingerprints.add("");
    }
    return List.copyOf(fingerprints);
  }

  private Optional<String> stageVerifyAndImport(GpgKeyModule.GpgKey key, Path downloaded)
      throws IOException {
    Path anchor = key.keyring().orElse(Path.of("/run/sysboot/gpg-import.key"));
    ProcessResult result =
        publisher.consume(
            downloaded,
            anchor,
            "0644",
            staged -> {
              Optional<String> failure = verifyFingerprint(key, staged, "root-owned staged key");
              if (failure.isPresent()) {
                return new ProcessResult(1, "", failure.orElseThrow(), Duration.ZERO);
              }
              return key.keyring().isPresent()
                  ? installKeyring(staged, key.keyring().orElseThrow())
                  : shellRunner.run(
                      List.of("sudo", "rpm", "--import", staged.toString()), Map.of(), TIMEOUT);
            });
    return result.isSuccess()
        ? Optional.empty()
        : Optional.of(
            "import verified key for "
                + key.displayName()
                + ": "
                + safeDetail(StepOutcome.detail(result), key));
  }

  private ProcessResult installKeyring(Path rootOwnedSource, Path keyring) throws IOException {
    byte[] decoded = OpenPgpKeyDecoder.decode(rootOwnedSource, maxKeyBytes);
    Path dearmored =
        Files.createTempFile(
            tempDirectory,
            "sysboot-gpg-key-",
            ".gpg",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      Files.write(dearmored, decoded);
      return publisher.publish(dearmored, keyring, "0644", ArtifactDigests.sha256(decoded));
    } finally {
      deleteQuietly(dearmored);
    }
  }

  private List<String> inspectCommand(Path keyFile) {
    return List.of(
        TrustedSystemExecutable.gpg().toString(),
        "--batch",
        "--no-options",
        "--show-keys",
        "--with-colons",
        keyFile.toString());
  }

  private void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The trust operation result is authoritative; temporary cleanup is best effort.
    }
  }

  private String normalize(String value) {
    return value.replace(" ", "").toUpperCase(Locale.ROOT);
  }

  private String failureMessage(Throwable failure, GpgKeyModule.GpgKey key) {
    String message =
        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    return safeDetail(message, key);
  }

  private String safeDetail(String text, GpgKeyModule.GpgKey key) {
    String withoutConfiguredSecret = text.replace(key.url(), key.publicUrl());
    return redactor.redact(URL_PARAMETERS.matcher(withoutConfiguredSecret).replaceAll("$1"));
  }
}
