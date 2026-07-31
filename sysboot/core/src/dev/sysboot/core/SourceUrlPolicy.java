package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SourceUrlPolicy {

  private static final Set<String> APT_SOURCE_OPTIONS = Set.of("arch", "signed-by");

  private SourceUrlPolicy() {}

  public static URI requireHttps(URI uri, String subject) {
    Objects.requireNonNull(uri, subject + " must not be null");
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new IllegalArgumentException(subject + " must be HTTPS without user-info");
    }
    return uri;
  }

  public static URI aptRepositoryUri(String sourceEntry) {
    Objects.requireNonNull(sourceEntry, "APT source entry must not be null");
    String value = sourceEntry.strip();
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("APT source entry must be a single line");
    }
    int cursor = skipType(value);
    if (cursor < value.length() && value.charAt(cursor) == '[') {
      int optionsEnd = value.indexOf(']', cursor + 1);
      if (optionsEnd < 0) {
        throw new IllegalArgumentException("APT source entry has unterminated options");
      }
      cursor = skipWhitespace(value, optionsEnd + 1);
    }
    int uriEnd = cursor;
    while (uriEnd < value.length() && !Character.isWhitespace(value.charAt(uriEnd))) {
      uriEnd++;
    }
    if (cursor == uriEnd) {
      throw new IllegalArgumentException("APT source entry must contain a repository URL");
    }
    return requireHttps(parseUri(value.substring(cursor, uriEnd)), "APT source repository URL");
  }

  public static void requireAuthenticatedAptSource(String sourceEntry, Path keyringPath) {
    Objects.requireNonNull(keyringPath, "APT keyring path must not be null");
    if (!keyringPath.isAbsolute()) {
      throw new IllegalArgumentException("APT keyring path must be absolute");
    }
    Map<String, String> options = aptOptions(sourceEntry);
    String signedBy = options.get("signed-by");
    if (signedBy == null) {
      throw new IllegalArgumentException("APT source entry requires exactly one signed-by option");
    }
    Path sourceKeyring = RepositoryDestinationPolicy.requireAptKeyring(Path.of(signedBy));
    if (!sourceKeyring.equals(keyringPath.normalize())) {
      throw new IllegalArgumentException(
          "APT source signed-by option must match the configured keyring path");
    }
  }

  private static Map<String, String> aptOptions(String sourceEntry) {
    String value = sourceEntry.strip();
    int cursor = skipType(value);
    if (cursor >= value.length() || value.charAt(cursor) != '[') {
      throw new IllegalArgumentException("APT source entry requires exactly one signed-by option");
    }
    int optionsEnd = value.indexOf(']', cursor + 1);
    if (optionsEnd < 0) {
      throw new IllegalArgumentException("APT source entry has unterminated options");
    }
    String optionsText = value.substring(cursor + 1, optionsEnd).strip();
    if (optionsText.isEmpty()) {
      throw new IllegalArgumentException("APT source options must not be empty");
    }
    var options = new HashMap<String, String>();
    for (String token : optionsText.split("\\s+")) {
      int equals = token.indexOf('=');
      if (equals <= 0 || equals == token.length() - 1 || token.indexOf('=', equals + 1) >= 0) {
        throw new IllegalArgumentException("APT source option must use name=value syntax");
      }
      String name = token.substring(0, equals).toLowerCase(java.util.Locale.ROOT);
      String optionValue = token.substring(equals + 1);
      if (!APT_SOURCE_OPTIONS.contains(name)) {
        throw new IllegalArgumentException("APT source option is not allowed: " + name);
      }
      if (options.putIfAbsent(name, optionValue) != null) {
        throw new IllegalArgumentException("APT source option must not be repeated: " + name);
      }
    }
    return Map.copyOf(options);
  }

  private static int skipType(String value) {
    int typeEnd = 0;
    while (typeEnd < value.length() && !Character.isWhitespace(value.charAt(typeEnd))) {
      typeEnd++;
    }
    String type = value.substring(0, typeEnd);
    if (!"deb".equals(type) && !"deb-src".equals(type)) {
      throw new IllegalArgumentException("APT source entry must start with deb or deb-src");
    }
    return skipWhitespace(value, typeEnd);
  }

  private static int skipWhitespace(String value, int cursor) {
    int result = cursor;
    while (result < value.length() && Character.isWhitespace(value.charAt(result))) {
      result++;
    }
    return result;
  }

  private static URI parseUri(String value) {
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("APT source repository URL must be valid", e);
    }
  }
}
