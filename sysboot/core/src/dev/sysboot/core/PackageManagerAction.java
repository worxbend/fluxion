package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record PackageManagerAction(String action, List<String> args) {

  public PackageManagerAction {
    Objects.requireNonNull(action, "Package manager action must not be null");
    Objects.requireNonNull(args, "Package manager action args must not be null");
    action = action.strip();
    if (action.isBlank()) {
      throw new IllegalArgumentException("Package manager action must not be blank");
    }
    args = args.stream().map(PackageManagerAction::requireArg).toList();
  }

  private static String requireArg(String arg) {
    Objects.requireNonNull(arg, "Package manager action arg must not be null");
    if (arg.isBlank()) {
      throw new IllegalArgumentException("Package manager action arg must not be blank");
    }
    return arg;
  }

  public String itemKey(int index) {
    return "action[" + index + "]";
  }
}
