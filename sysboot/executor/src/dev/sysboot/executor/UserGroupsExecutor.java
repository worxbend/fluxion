package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.UserGroupsModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adds a user to supplementary groups, and reports when the current session has not picked them up.
 *
 * <p>The mechanism rests on an asymmetry in {@code id}: {@code id -nG} with no argument reports the
 * calling process's own credentials, which the kernel fixed at login and will never change, while
 * {@code id -nG <user>} re-reads the group database through NSS and therefore reflects a {@code
 * usermod} from seconds ago. Comparing the two tells us whether the change has landed in the
 * database but not yet in the session — which is exactly when a logout is required.
 */
public final class UserGroupsExecutor {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

  private final ShellRunner shellRunner;

  public UserGroupsExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(UserGroupsModule module) {
    String user = resolveUser(module);
    Set<String> inDatabase = groupsOf(user);
    List<String> missing = module.groups().stream().filter(g -> !inDatabase.contains(g)).toList();

    var failures = new ArrayList<String>();
    for (String group : missing) {
      addGroup(module, user, group).ifPresent(failures::add);
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  StepResult executeItem(UserGroupsModule module, String group) {
    String itemKey = module.itemKey(group);
    String user = resolveUser(module);
    if (groupsOf(user).contains(group)) {
      return new StepResult.Success(itemKey, Duration.ZERO);
    }
    Optional<String> failure = addGroup(module, user, group);
    return failure
        .<StepResult>map(message -> new StepResult.Failure(itemKey, message, 1, Duration.ZERO))
        .orElseGet(() -> new StepResult.Success(itemKey, Duration.ZERO));
  }

  /**
   * Groups the user now has in the database but not in the current login session.
   *
   * <p>Non-empty means the work succeeded and a logout is still required for it to take effect.
   */
  public List<String> groupsPendingLogout(UserGroupsModule module) {
    if (!module.logoutCheckpoint()) {
      return List.of();
    }
    String user = resolveUser(module);
    if (!user.equals(currentUser())) {
      // Changing another account never affects this session.
      return List.of();
    }
    Set<String> session = currentSessionGroups();
    Set<String> database = groupsOf(user);
    return module.groups().stream()
        .filter(database::contains)
        .filter(group -> !session.contains(group))
        .toList();
  }

  /** Command preview used by {@code plan} and {@code dry-run}. */
  public List<String> commandPreview(UserGroupsModule module) {
    var preview = new ArrayList<String>();
    if (module.createMissing()) {
      module.groups().forEach(group -> preview.addAll(List.of("sudo", "groupadd", "-f", group)));
    }
    preview.addAll(
        List.of("sudo", "usermod", "-aG", String.join(",", module.groups()), resolveUser(module)));
    return List.copyOf(preview);
  }

  List<String> commandPreview(UserGroupsModule module, String group) {
    var preview = new ArrayList<String>();
    if (module.createMissing()) {
      preview.addAll(List.of("sudo", "groupadd", "-f", group));
    }
    preview.addAll(List.of("sudo", "usermod", "-aG", group, resolveUser(module)));
    return List.copyOf(preview);
  }

  /** Probe key check: the user is already a member in the group database. */
  public boolean alreadyMember(UserGroupsModule module, String group) {
    return groupsOf(resolveUser(module)).contains(group);
  }

  private Optional<String> addGroup(UserGroupsModule module, String user, String group) {
    if (module.createMissing()) {
      // -f makes this a no-op when the group exists, so the previewed command is what runs.
      run(List.of("sudo", "groupadd", "-f", group));
    } else if (!groupExists(group)) {
      return Optional.of(
          "group '"
              + group
              + "' does not exist; install the package that provides it or set createMissing:"
              + " true");
    }
    ProcessResult result = run(List.of("sudo", "usermod", "-aG", group, user));
    return result.isSuccess()
        ? Optional.empty()
        : Optional.of("failed to add " + user + " to " + group + ": " + StepOutcome.detail(result));
  }

  private boolean groupExists(String group) {
    return run(List.of("getent", "group", group)).isSuccess();
  }

  private Set<String> groupsOf(String user) {
    return parseGroups(run(List.of("id", "-nG", user)));
  }

  private Set<String> currentSessionGroups() {
    return parseGroups(run(List.of("id", "-nG")));
  }

  private Set<String> parseGroups(ProcessResult result) {
    if (!result.isSuccess()) {
      return Set.of();
    }
    return new LinkedHashSet<>(
        Arrays.stream(result.stdout().strip().split("\\s+")).filter(s -> !s.isBlank()).toList());
  }

  private String resolveUser(UserGroupsModule module) {
    return TargetUserResolver.resolve(module.user());
  }

  /**
   * The account the profile is really for.
   *
   * <p>{@code SUDO_USER} takes precedence: when Fluxion itself was launched with sudo, {@code
   * user.name} is {@code root}, and adding root to the docker group is never what was meant.
   */
  private String currentUser() {
    return TargetUserResolver.resolve();
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), COMMAND_TIMEOUT);
  }
}
