package dev.sysboot.executor;

import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class LinuxHostFactsProvider implements HostFactsProvider {

  private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));

  private final Path osReleaseFile;
  private final Map<String, String> environment;
  private final Map<String, String> systemProperties;

  public LinuxHostFactsProvider() {
    this(Path.of("/etc/os-release"), System.getenv(), systemProperties());
  }

  LinuxHostFactsProvider(
      Path osReleaseFile, Map<String, String> environment, Map<String, String> systemProperties) {
    this.osReleaseFile = osReleaseFile;
    this.environment = Map.copyOf(environment);
    this.systemProperties = Map.copyOf(systemProperties);
  }

  @Override
  public HostFacts facts() {
    Map<String, String> osRelease = readOsRelease();
    return new HostFacts(
        "linux",
        optional(osRelease.get("ID")).map(this::normalizeDistribution),
        optional(osRelease.get("VERSION_ID")),
        codename(osRelease),
        normalizeArchitecture(systemProperties.getOrDefault("os.arch", "")));
  }

  @Override
  public boolean commandExists(String command) {
    String normalized = command == null ? "" : command.strip();
    if (normalized.isBlank() || normalized.contains("/") || normalized.contains("\\")) {
      return false;
    }
    return pathDirectories().map(dir -> dir.resolve(normalized)).anyMatch(this::executableFile);
  }

  private Map<String, String> readOsRelease() {
    if (!Files.isRegularFile(osReleaseFile)) {
      return Map.of();
    }
    try {
      return parseOsRelease(Files.readAllLines(osReleaseFile));
    } catch (NoSuchFileException | AccessDeniedException | MalformedInputException e) {
      return Map.of();
    } catch (IOException e) {
      return Map.of();
    }
  }

  private Map<String, String> parseOsRelease(Iterable<String> lines) {
    var values = new LinkedHashMap<String, String>();
    for (String line : lines) {
      parseOsReleaseLine(line).ifPresent(entry -> values.put(entry.key(), entry.value()));
    }
    return Map.copyOf(values);
  }

  private Optional<Entry> parseOsReleaseLine(String line) {
    String stripped = line.strip();
    if (stripped.isBlank() || stripped.startsWith("#")) {
      return Optional.empty();
    }
    int separator = stripped.indexOf('=');
    if (separator < 1) {
      return Optional.empty();
    }
    String key = stripped.substring(0, separator);
    String value = unquote(stripped.substring(separator + 1));
    return Optional.of(new Entry(key, value));
  }

  private String unquote(String value) {
    String stripped = value.strip();
    if (stripped.length() < 2 || !matchingQuotes(stripped)) {
      return stripped;
    }
    return stripped.substring(1, stripped.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private boolean matchingQuotes(String value) {
    char first = value.charAt(0);
    char last = value.charAt(value.length() - 1);
    return (first == '"' && last == '"') || (first == '\'' && last == '\'');
  }

  private Optional<String> codename(Map<String, String> osRelease) {
    return optional(osRelease.get("VERSION_CODENAME"))
        .or(() -> optional(osRelease.get("UBUNTU_CODENAME")));
  }

  private String normalizeDistribution(String distribution) {
    return distribution.strip().toLowerCase(Locale.ROOT);
  }

  private String normalizeArchitecture(String architecture) {
    String normalized = architecture.strip().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "unknown";
    }
    return switch (normalized) {
      case "x86_64", "amd64" -> "amd64";
      case "aarch64", "arm64" -> "arm64";
      case "armv7l", "armv7", "armhf" -> "armv7";
      case "i386", "i486", "i586", "i686", "x86" -> "386";
      default -> normalized;
    };
  }

  private java.util.stream.Stream<Path> pathDirectories() {
    return optional(environment.get("PATH"))
        .stream()
        .flatMap(path -> PATH_SEPARATOR.splitAsStream(path).filter(segment -> !segment.isBlank()))
        .map(Path::of);
  }

  private boolean executableFile(Path path) {
    return Files.isRegularFile(path) && Files.isExecutable(path);
  }

  private Optional<String> optional(String value) {
    return Optional.ofNullable(value).map(String::strip).filter(v -> !v.isBlank());
  }

  private static Map<String, String> systemProperties() {
    return Map.of("os.arch", System.getProperty("os.arch", ""));
  }

  private record Entry(String key, String value) {}
}
