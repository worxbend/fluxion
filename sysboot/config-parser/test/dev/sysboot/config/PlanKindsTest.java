package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the registry that replaced the five hand-maintained kind sets. The failure these pin is
 * drift: a kind that exists in one place and not another, or a kind that ships without a reference
 * entry telling a user what its spec fields are.
 */
class PlanKindsTest {

  private static final List<String> EXPECTED_KINDS =
      List.of(
          "apt-packages",
          "aur-packages",
          "cargo-packages",
          "dnf-packages",
          "pacman-packages",
          "zypper-packages",
          "sdkman-packages",
          "flatpak-packages",
          "binary-downloads",
          "shell-scripts",
          "commands",
          "file-writes",
          "nerd-fonts",
          "dotfiles-apply",
          "binstaller-profile",
          "user-groups",
          "git-config",
          "git-repo",
          "systemd-unit",
          "system-setting",
          "system-update",
          "gpg-key",
          "tool-packages",
          "zypper-repository",
          "interrupt");

  @Test
  void theSupportedKindSetIsPinned() {
    assertThat(PlanKinds.ids()).containsExactlyElementsOf(EXPECTED_KINDS);
  }

  @Test
  void everyKindCanBeLookedUpAndCarriesBothHalvesOfItsBehaviour() {
    for (String id : PlanKinds.ids()) {
      assertThat(PlanKinds.find(id))
          .withFailMessage("kind '%s' is listed but not resolvable", id)
          .hasValueSatisfying(
              kind -> {
                assertThat(kind.category()).isNotNull();
                assertThat(kind.specCheck()).isNotNull();
                assertThat(kind.mapper()).isNotNull();
              });
    }
  }

  @Test
  void packageActionsAreDeclaredOnlyForPackageKinds() {
    for (String id : PlanKinds.ids()) {
      PlanKinds.PlanKind kind = PlanKinds.find(id).orElseThrow();
      if (kind.category() != PlanKinds.Category.PACKAGES) {
        assertThat(kind.packageActions())
            .withFailMessage("non-package kind '%s' declares package actions", id)
            .isEmpty();
      }
    }
  }

  @Test
  void unknownKindsAreNotResolved() {
    assertThat(PlanKinds.find("brew-packages")).isEmpty();
    assertThat(PlanKinds.find("APT-PACKAGES")).isEmpty();
  }

  @Test
  void aNearMissSuggestsTheKindItAlmostSpelled() {
    assertThat(PlanKinds.closestId("apt-package")).contains("apt-packages");
    assertThat(PlanKinds.closestId("APT-Packages")).contains("apt-packages");
    assertThat(PlanKinds.closestId("systemd-units")).contains("systemd-unit");
    assertThat(PlanKinds.closestId("gitconfig")).contains("git-config");
  }

  @Test
  void somethingThatIsNotAKindAtAllSuggestsNothing() {
    assertThat(PlanKinds.closestId("helm")).isEmpty();
    assertThat(PlanKinds.closestId("ansible-playbook")).isEmpty();
    assertThat(PlanKinds.closestId("")).isEmpty();
    assertThat(PlanKinds.closestId(null)).isEmpty();
  }

  @Test
  void everyKindHasAReferenceEntryInTheManifestDocs() throws IOException {
    String reference = Files.readString(manifestReference());

    for (String id : PlanKinds.ids()) {
      assertThat(reference)
          .withFailMessage(
              "plan kind '%s' is supported but has no `%s` entry in workstation-profile.md", id, id)
          .contains("`" + id + "`");
    }
  }

  /**
   * Mill runs tests from a working directory under {@code out/}, so the reference is found by
   * walking up until a directory holds it rather than by guessing a fixed relative depth.
   */
  private Path manifestReference() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("sysboot/docs/workstation-profile.md");
      if (Files.exists(candidate)) {
        return candidate;
      }
      Path sibling = dir.resolve("docs/workstation-profile.md");
      if (Files.exists(sibling)) {
        return sibling;
      }
    }
    throw new IllegalStateException(
        "workstation-profile.md not found above " + Path.of("").toAbsolutePath());
  }
}
