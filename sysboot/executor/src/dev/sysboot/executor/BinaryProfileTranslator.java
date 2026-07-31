package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Translates a {@code compiled-binary} step into a binstaller {@code BinaryDistributionProfile}.
 *
 * <p>binstaller already resolves, downloads, verifies, extracts (zip, tar.gz <em>and</em> tar.xz),
 * installs and symlinks binary tools. Fluxion's own installer only handles tar.gz; profiles
 * historically needed hand-written download and extraction commands for other formats. Translating
 * instead of extending removes that limitation without Fluxion growing a second archive
 * implementation.
 *
 * <p>Translation is deliberately partial. {@link #translate} returns empty rather than guessing
 * when binstaller cannot express the step faithfully, and the caller falls back to the built-in
 * installer. Silently dropping a verification or mis-mapping an archive member would be far worse
 * than not delegating.
 */
final class BinaryProfileTranslator {

  private static final String APPS_DIR = "${appsDir}";
  private static final Path USER_HOME =
      Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
  private static final Path LOCAL_APPS_DIR = USER_HOME.resolve(".apps");

  /** Why a step could not be expressed as a binstaller profile. */
  record Refusal(String reason) {}

  private BinaryProfileTranslator() {}

  /**
   * Renders a single-entry profile, or explains why it cannot be rendered.
   *
   * @return the profile YAML, or a {@link Refusal} the caller should fall back on
   */
  static Object translate(CompiledBinaryModule module) {
    Optional<Refusal> refusal = refuse(module);
    if (refusal.isPresent()) {
      return refusal.orElseThrow();
    }
    return render(module);
  }

  private static Optional<Refusal> refuse(CompiledBinaryModule module) {
    if (module.signatureUrl().isPresent()) {
      // binstaller verifies SHA-256 and sigstore-signs its own releases, but has no
      // `gpg --verify` path for third-party artifacts. Delegating would silently drop a check the
      // profile explicitly asked for.
      return Optional.of(new Refusal("binstaller cannot verify a detached GPG signature"));
    }
    if (module.checksum().filter(BinaryProfileTranslator::isNotSha256).isPresent()) {
      return Optional.of(new Refusal("binstaller supports only SHA-256 checksums"));
    }
    if (!safePathSegment(module.name().value()) || !safePathSegment(module.binaryName())) {
      return Optional.of(new Refusal("tool and binary names must be safe path segments"));
    }
    if (archiveType(module).isPresent() && module.archivePath().isEmpty()) {
      // An archive with no declared member cannot be mapped: binstaller requires an explicit
      // from/to, and guessing which member is the binary is exactly how you install the wrong file.
      return Optional.of(
          new Refusal("an archive step needs archivePath so the member can be mapped"));
    }
    if (module.stripComponents() > 0) {
      return Optional.of(new Refusal("stripComponents has no binstaller equivalent"));
    }
    if (symlinks(module, module.name().value()).stream()
        .anyMatch(BinaryProfileTranslator::usesSudo)) {
      return Optional.of(
          new Refusal(
              "binstaller delegation cannot perform privileged symlinks through Fluxion's"
                  + " authenticated runner"));
    }
    return Optional.empty();
  }

  private static boolean isNotSha256(Checksum checksum) {
    return !"SHA-256".equalsIgnoreCase(checksum.algorithm())
        && !"SHA256".equalsIgnoreCase(checksum.algorithm());
  }

  private static String render(CompiledBinaryModule module) {
    String tool = module.name().value();
    var spec = new LinkedHashMap<String, Object>();
    spec.put("installDir", APPS_DIR + "/" + tool);
    spec.put("download", download(module));
    spec.put("executables", List.of(executable(module)));

    List<Map<String, Object>> symlinks = symlinks(module, tool);
    if (!symlinks.isEmpty()) {
      spec.put("symlinks", symlinks);
    }

    var entry = new LinkedHashMap<String, Object>();
    entry.put("name", tool);
    entry.put("kind", "binary-tool");
    entry.put("spec", spec);

    var policy = new LinkedHashMap<String, Object>();
    policy.put("mode", "developer");
    policy.put("appsDir", LOCAL_APPS_DIR.toString());
    policy.put("continueOnError", module.continueOnError());
    policy.put("allowSudoSymlinks", symlinks.stream().anyMatch(BinaryProfileTranslator::usesSudo));

    var profileSpec = new LinkedHashMap<String, Object>();
    profileSpec.put("policy", policy);
    profileSpec.put("plan", List.of(entry));

    var root = new LinkedHashMap<String, Object>();
    root.put("apiVersion", "binstaller.io/v1alpha1");
    root.put("kind", "BinaryDistributionProfile");
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("name", "fluxion-" + tool);
    root.put("metadata", metadata);
    root.put("spec", profileSpec);
    return Yaml.render(root);
  }

  private static Map<String, Object> download(CompiledBinaryModule module) {
    var download = new LinkedHashMap<String, Object>();
    download.put("url", module.url().value().toString());
    download.put("filename", filename(module));
    checksum(module).ifPresent(value -> download.put("checksum", value));
    archiveType(module).ifPresent(type -> download.put("archive", archive(module, type)));
    return download;
  }

  /**
   * Archive block, built with ordered maps.
   *
   * <p>{@code Map.of} randomises iteration order per JVM, which would make the generated profile
   * differ between runs — breaking both reproducibility and any fingerprint taken over it.
   */
  private static Map<String, Object> archive(CompiledBinaryModule module, String type) {
    var mapping = new LinkedHashMap<String, Object>();
    mapping.put("from", module.archivePath().orElseThrow());
    mapping.put("to", "bin/" + module.binaryName());

    var extract = new LinkedHashMap<String, Object>();
    extract.put("files", List.of(mapping));

    var archive = new LinkedHashMap<String, Object>();
    archive.put("type", type);
    archive.put("extract", extract);
    return archive;
  }

  private static Optional<Map<String, Object>> checksum(CompiledBinaryModule module) {
    if (module.checksum().isPresent()) {
      var configured = new LinkedHashMap<String, Object>();
      configured.put("algorithm", "sha256");
      configured.put("value", module.checksum().orElseThrow().value());
      return Optional.of(configured);
    }
    return module.checksumUrl().map(BinaryProfileTranslator::discoveredChecksum);
  }

  private static Map<String, Object> discoveredChecksum(dev.sysboot.core.BinaryUrl url) {
    var discover = new LinkedHashMap<String, Object>();
    discover.put("type", "sha256sum");
    discover.put("url", url.value().toString());

    var checksum = new LinkedHashMap<String, Object>();
    checksum.put("algorithm", "sha256");
    checksum.put("discover", discover);
    return checksum;
  }

  private static Map<String, Object> executable(CompiledBinaryModule module) {
    var executable = new LinkedHashMap<String, Object>();
    executable.put("path", "bin/" + module.binaryName());
    module.installMode().ifPresent(mode -> executable.put("mode", mode));
    return executable;
  }

  /**
   * Absolute install paths become privileged symlinks.
   *
   * <p>binstaller confines every install under {@code policy.appsDir}, so a step targeting {@code
   * /usr/local/bin/rg} installs into the apps dir and links from there — which is what the built-in
   * installer effectively does anyway, only with {@code sudo cp}.
   */
  private static List<Map<String, Object>> symlinks(CompiledBinaryModule module, String tool) {
    var symlinks = new ArrayList<Map<String, Object>>();
    Path canonicalTarget = appsBinaryPath(module);
    String target = APPS_DIR + "/" + tool + "/bin/" + module.binaryName();
    if (!module.installPath().equals(canonicalTarget)) {
      symlinks.add(symlink(module.installPath(), target));
    }
    module
        .symlinkPath()
        .filter(link -> !link.equals(canonicalTarget))
        .ifPresent(link -> symlinks.add(symlink(link, target)));
    return List.copyOf(symlinks);
  }

  private static Map<String, Object> symlink(Path path, String target) {
    var symlink = new LinkedHashMap<String, Object>();
    symlink.put("path", path.toString());
    symlink.put("target", target);
    symlink.put("sudo", requiresSudo(path));
    return symlink;
  }

  static Path appsBinaryPath(CompiledBinaryModule module) {
    return LOCAL_APPS_DIR
        .resolve(module.name().value())
        .resolve("bin")
        .resolve(module.binaryName());
  }

  private static boolean requiresSudo(Path path) {
    return !path.startsWith(USER_HOME);
  }

  private static boolean usesSudo(Map<String, Object> symlink) {
    return Boolean.TRUE.equals(symlink.get("sudo"));
  }

  private static boolean safePathSegment(String value) {
    return !value.isBlank()
        && value.indexOf('/') < 0
        && value.indexOf('\\') < 0
        && !".".equals(value)
        && !"..".equals(value);
  }

  private static String filename(CompiledBinaryModule module) {
    String path = module.url().value().getPath();
    int slash = path.lastIndexOf('/');
    String candidate = slash < 0 ? path : path.substring(slash + 1);
    return candidate.isBlank() ? module.binaryName() : candidate;
  }

  /** Archive type binstaller should use, or empty for a direct binary download. */
  static Optional<String> archiveType(CompiledBinaryModule module) {
    String path = module.url().value().getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".tar.gz") || path.endsWith(".tgz")) {
      return Optional.of("tar.gz");
    }
    if (path.endsWith(".tar.xz")) {
      return Optional.of("tar.xz");
    }
    if (path.endsWith(".zip")) {
      return Optional.of("zip");
    }
    return Optional.empty();
  }
}
