package dev.sysboot.core;

import java.util.Optional;

public interface SudoPasswordProvider {
  Optional<char[]> requestPassword(String prompt);
}
