package dev.sysboot.core;

import java.net.URI;
import java.util.Objects;

/** Projects a request URL into a stable value that is safe to persist or display. */
public final class PublicUrl {

  private PublicUrl() {}

  public static String from(URI value) {
    Objects.requireNonNull(value);
    return from(value.toString());
  }

  public static String from(String value) {
    Objects.requireNonNull(value);
    String withoutRequestData = beforeRequestData(value);
    int scheme = withoutRequestData.indexOf("://");
    if (scheme < 0) {
      return withoutRequestData;
    }
    int authorityStart = scheme + 3;
    int authorityEnd = withoutRequestData.indexOf('/', authorityStart);
    if (authorityEnd < 0) {
      authorityEnd = withoutRequestData.length();
    }
    int userInfoEnd = withoutRequestData.lastIndexOf('@', authorityEnd);
    if (userInfoEnd < authorityStart) {
      return withoutRequestData;
    }
    return withoutRequestData.substring(0, authorityStart)
        + withoutRequestData.substring(userInfoEnd + 1);
  }

  private static String beforeRequestData(String value) {
    int query = value.indexOf('?');
    int fragment = value.indexOf('#');
    int end = query < 0 ? value.length() : query;
    if (fragment >= 0) {
      end = Math.min(end, fragment);
    }
    return value.substring(0, end);
  }
}
