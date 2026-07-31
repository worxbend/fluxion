package dev.sysboot.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SudoCommand {

  private static final List<String> FIXED_EXECUTABLES = List.of("/usr/bin/sudo", "/bin/sudo");

  private SudoCommand() {}

  static String executable() {
    return TrustedSystemExecutable.resolve("sudo").toString();
  }

  static boolean isInvocation(List<String> command) {
    if (command.isEmpty()) {
      return false;
    }
    String first = command.getFirst();
    return "sudo".equals(first) || FIXED_EXECUTABLES.contains(first);
  }

  static List<String> forEffect(List<String> command) {
    if (!isInvocation(command)) {
      return List.copyOf(command);
    }
    if (command.equals(validateWithoutPrompt()) || command.equals(validateWithPassword())) {
      return List.copyOf(command);
    }
    int targetIndex = targetIndex(command);
    Path target = TrustedSystemExecutable.resolve(command.get(targetIndex));
    var hardened = new ArrayList<String>(command.size() + 2);
    hardened.add(executable());
    hardened.add("-n");
    hardened.add("--");
    hardened.add(target.toString());
    hardened.addAll(command.subList(targetIndex + 1, command.size()));
    return List.copyOf(hardened);
  }

  private static int targetIndex(List<String> command) {
    if (command.size() >= 4 && "-n".equals(command.get(1)) && "--".equals(command.get(2))) {
      return 3;
    }
    if (command.size() >= 2 && !command.get(1).startsWith("-")) {
      return 1;
    }
    throw new IllegalArgumentException("Privileged command has no executable target");
  }

  static List<String> validateWithoutPrompt() {
    return List.of(executable(), "-n", "-v");
  }

  static List<String> validateWithPassword() {
    return List.of(executable(), "-S", "-p", "", "-v");
  }

  static List<String> invalidate() {
    return List.of(executable(), "-k");
  }
}
