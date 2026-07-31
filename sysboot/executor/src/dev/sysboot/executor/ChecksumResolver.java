package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class ChecksumResolver {

  private static final Pattern SAFE_COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

  private final BinaryDownloadClient downloadClient;

  ChecksumResolver() {
    this(new HttpBinaryDownloadClient());
  }

  ChecksumResolver(HttpClient httpClient) {
    this(new HttpBinaryDownloadClient(httpClient));
  }

  ChecksumResolver(BinaryDownloadClient downloadClient) {
    this.downloadClient = downloadClient;
  }

  Optional<Checksum> resolve(CompiledBinaryModule module) throws IOException {
    if (module.checksum().isPresent()) {
      return module.checksum();
    }
    if (module.checksumUrl().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Checksum("SHA-256", downloadChecksum(module)));
  }

  private String downloadChecksum(CompiledBinaryModule module) throws IOException {
    String document = downloadClient.downloadText(module.checksumUrl().orElseThrow().value());
    return parseSha256(document, assetName(module));
  }

  static String parseSha256(String body, String assetName) throws IOException {
    var lines = body.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    if (lines.size() == 1 && lines.getFirst().matches("[0-9a-fA-F]{64}")) {
      return lines.getFirst().toLowerCase(Locale.ROOT);
    }
    String matchedDigest = null;
    for (String line : lines) {
      String[] fields = line.split("\\s+", 2);
      if (fields.length == 2 && checksumAsset(fields[1]).filter(assetName::equals).isPresent()) {
        if (matchedDigest != null) {
          throw new IOException("Checksum document contains duplicate entries for " + assetName);
        }
        matchedDigest = requireDocumentSha256(fields[0], assetName);
      }
    }
    if (matchedDigest != null) {
      return matchedDigest;
    }
    throw new IOException("Checksum document does not contain a SHA-256 entry for " + assetName);
  }

  private String assetName(CompiledBinaryModule module) throws IOException {
    String path = module.url().value().getPath();
    int separator = path.lastIndexOf('/');
    String assetName = separator >= 0 ? path.substring(separator + 1) : path;
    if (assetName.isBlank()) {
      throw new IOException("Binary URL does not identify an asset name");
    }
    return assetName;
  }

  private static String requireDocumentSha256(String digest, String assetName) throws IOException {
    if (!digest.matches("[0-9a-fA-F]{64}")) {
      throw new IOException("Malformed SHA-256 entry for " + assetName);
    }
    return digest.toLowerCase(Locale.ROOT);
  }

  static String parseSidecarSha256(String contents, String assetName) {
    String line =
        contents
            .lines()
            .map(String::strip)
            .filter(entry -> !entry.isBlank())
            .findFirst()
            .orElseThrow(() -> failure(assetName, "checksum sidecar is empty"));
    String[] fields = line.split("\\s+", 2);
    if (fields.length == 2 && checksumAsset(fields[1]).filter(assetName::equals).isEmpty()) {
      throw failure(assetName, "checksum entry names a different asset");
    }
    return requireSha256(fields[0], assetName);
  }

  static String parseChecksumsFileSha256(String contents, String assetName) {
    List<String> matches =
        contents
            .lines()
            .map(String::strip)
            .map(line -> line.split("\\s+", 2))
            .filter(fields -> fields.length == 2)
            .filter(fields -> checksumAsset(fields[1]).filter(assetName::equals).isPresent())
            .map(fields -> requireSha256(fields[0], assetName))
            .toList();
    if (matches.isEmpty()) {
      throw failure(assetName, "checksum entry is missing");
    }
    if (matches.size() > 1) {
      throw failure(assetName, "checksum entry is duplicated");
    }
    return matches.getFirst();
  }

  private static Optional<String> checksumAsset(String field) {
    String asset = field.strip();
    if (asset.startsWith("*")) {
      asset = asset.substring(1);
    }
    if (asset.startsWith("./")) {
      asset = asset.substring(2);
    }
    if (asset.isBlank()
        || asset.startsWith("/")
        || asset.contains("\\")
        || asset.contains("//")
        || asset.codePoints().anyMatch(Character::isISOControl)) {
      return Optional.empty();
    }
    String[] components = asset.split("/", -1);
    for (String component : components) {
      if (component.equals(".")
          || component.equals("..")
          || !SAFE_COMPONENT.matcher(component).matches()) {
        return Optional.empty();
      }
    }
    return Optional.of(components[components.length - 1]);
  }

  private static String requireSha256(String digest, String assetName) {
    if (!digest.matches("[0-9a-fA-F]{64}")) {
      throw failure(assetName, "SHA-256 digest is malformed");
    }
    return digest;
  }

  private static ToolResolutionException failure(String assetName, String detail) {
    return new ToolResolutionException("Cannot verify " + assetName + ": " + detail);
  }
}
