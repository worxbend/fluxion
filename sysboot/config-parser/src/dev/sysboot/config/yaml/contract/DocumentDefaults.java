package dev.sysboot.config.yaml.contract;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DocumentDefaults {

  private DocumentDefaults() {}

  static <T> Optional<T> optional(T value) {
    return Optional.ofNullable(value);
  }

  static <T> List<T> list(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  static <K, V> Map<K, V> map(Map<K, V> values) {
    return values == null ? Map.of() : Map.copyOf(values);
  }
}
