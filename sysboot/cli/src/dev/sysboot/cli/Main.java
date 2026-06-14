package dev.sysboot.cli;

import picocli.CommandLine;

public final class Main {

  private Main() {}

  public static void main(String[] args) {
    int exitCode =
        new CommandLine(new SysbootCommand())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
    System.exit(exitCode);
  }
}
