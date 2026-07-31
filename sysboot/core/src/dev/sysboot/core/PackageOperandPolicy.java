package dev.sysboot.core;

import java.util.List;
import java.util.Set;

final class PackageOperandPolicy {

  private static final Set<String> APT_FLAGS =
      Set.of("--with-new-pkgs", "--no-remove", "--trivial-only", "--download-only");
  private static final Set<String> DNF_FLAGS =
      Set.of("--refresh", "--best", "--nobest", "--allowerasing", "--skip-broken");
  private static final Set<String> PACMAN_FLAGS = Set.of("--needed");
  private static final Set<String> ZYPPER_FLAGS =
      Set.of("--allow-vendor-change", "--no-allow-vendor-change", "--no-recommends");

  private PackageOperandPolicy() {}

  static void requireSafeActions(
      PackageManagerKind manager, List<PackageManagerAction> configuredActions) {
    for (PackageManagerAction action : configuredActions) {
      switch (manager) {
        case APT -> validateApt(action);
        case DNF -> validateDnf(action);
        case PACMAN -> validatePacman(action);
        case ZYPPER -> validateZypper(action);
        default -> {
          if (!configuredActions.isEmpty()) {
            throw new IllegalArgumentException(
                manager.name().toLowerCase() + " does not support package-manager actions");
          }
        }
      }
    }
  }

  private static void validateApt(PackageManagerAction action) {
    switch (action.action()) {
      case "update" -> requireNoArgs("apt update", action.args());
      case "upgrade", "dist-upgrade" -> requireAllowedFlags("apt", action.args(), APT_FLAGS);
      default -> throw unsupported("apt", action);
    }
  }

  private static void validateDnf(PackageManagerAction action) {
    switch (action.action()) {
      case "check-update", "upgrade" -> requireAllowedFlags("dnf", action.args(), DNF_FLAGS);
      case "swap" -> requirePackageOperands("dnf swap", action.args(), 2);
      case "groupupdate", "group-update" ->
          requirePackageOperands("dnf groupupdate", action.args(), 1);
      default -> throw unsupported("dnf", action);
    }
  }

  private static void validatePacman(PackageManagerAction action) {
    switch (action.action()) {
      case "sync-upgrade", "syu", "upgrade" ->
          requireAllowedFlags("pacman", action.args(), PACMAN_FLAGS);
      default -> throw unsupported("pacman", action);
    }
  }

  private static void validateZypper(PackageManagerAction action) {
    switch (action.action()) {
      case "refresh", "update", "dup" -> requireAllowedFlags("zypper", action.args(), ZYPPER_FLAGS);
      case "dup-from" -> {
        if (action.args().size() != 1) {
          throw new IllegalArgumentException("zypper dup-from requires exactly one repository id");
        }
        RepositoryIdentifierPolicy.requireSafe(action.args().getFirst(), "Zypper repository id");
      }
      default -> throw unsupported("zypper", action);
    }
  }

  private static void requireNoArgs(String action, List<String> args) {
    if (!args.isEmpty()) {
      throw new IllegalArgumentException(action + " does not accept configured arguments");
    }
  }

  private static void requireAllowedFlags(String manager, List<String> args, Set<String> allowed) {
    for (String arg : args) {
      if (!allowed.contains(arg)) {
        throw new IllegalArgumentException(
            manager + " action argument is not an allowed fixed option: " + arg);
      }
    }
  }

  private static void requirePackageOperands(String action, List<String> args, int minimum) {
    if (args.size() < minimum) {
      throw new IllegalArgumentException(action + " requires package operands");
    }
    args.forEach(PackageName::new);
  }

  private static IllegalArgumentException unsupported(String manager, PackageManagerAction action) {
    return new IllegalArgumentException("Unsupported " + manager + " action: " + action.action());
  }
}
