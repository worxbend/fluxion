package dev.sysboot.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds a user to supplementary Unix groups.
 *
 * <p>This is the one operation in a typical profile where the command succeeding is not the same as
 * the machine being in the desired state: {@code usermod -aG docker $USER} returns 0 and {@code
 * docker ps} still says permission denied until the user logs out. The executor detects that and
 * raises a logout checkpoint, so the profile does not have to predict it.
 *
 * <p>Membership is append-only. There is deliberately no removal syntax: quietly dropping a user
 * out of a group they rely on is a far worse failure than having to run {@code gpasswd -d} by hand,
 * and a profile that could do it silently would be dangerous to share.
 *
 * @param user the account to modify; empty means the user running Fluxion, resolved at execution
 *     time because the mapper has no host facts
 * @param createMissing create a group that does not exist rather than failing. Off by default: the
 *     groups people want here ({@code docker}, {@code libvirt}, {@code kvm}) are created by their
 *     package, so a missing one usually means a typo or a package that was not installed, and
 *     silently creating a real but useless group hides that.
 * @param logoutCheckpoint raise a restart checkpoint when the current session lacks a group that
 *     was just added. Turn off for containers and image builds, where there is no session to log
 *     out of.
 */
public record UserGroupsModule(
    ModuleName name,
    Optional<String> user,
    List<String> groups,
    boolean createMissing,
    boolean logoutCheckpoint,
    Optional<String> checkpointMessage,
    boolean continueOnError)
    implements BootstrapModule {

  public UserGroupsModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(user);
    Objects.requireNonNull(checkpointMessage);
    groups = List.copyOf(Objects.requireNonNull(groups));
    if (groups.isEmpty()) {
      throw new IllegalArgumentException("user-groups requires at least one group");
    }
    if (groups.stream().distinct().count() != groups.size()) {
      throw new IllegalArgumentException("user-groups must not repeat a group");
    }
    if (groups.stream().anyMatch(group -> group == null || group.isBlank())) {
      throw new IllegalArgumentException("user-groups must not contain a blank group");
    }
  }

  public UserGroupsModule(ModuleName name, List<String> groups) {
    this(name, Optional.empty(), groups, false, true, Optional.empty(), false);
  }

  /**
   * State and event key for one group.
   *
   * <p>Unqualified for the common implicit-user case so {@code fluxion status} stays readable, and
   * qualified when a user is named so two modules targeting different users cannot collide.
   */
  public String itemKey(String group) {
    return user.map(name -> name + ":" + group).orElse(group);
  }

  public String defaultCheckpointMessage() {
    return "Log out and back in so the new group membership ("
        + String.join(", ", groups)
        + ") takes effect.";
  }
}
