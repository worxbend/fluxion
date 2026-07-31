package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedDestinationAncestryTest {

  @TempDir Path tempDirectory;

  @Test
  void rejectsDestinationAndAncestorSymlinksWithoutFollowingThem() throws IOException {
    String owner = Files.getOwner(tempDirectory).getName();
    Path realParent = Files.createDirectory(tempDirectory.resolve("real"));
    Path ancestorLink = Files.createSymbolicLink(tempDirectory.resolve("keys"), realParent);

    assertThatThrownBy(
            () ->
                TrustedDestinationAncestry.requireSafe(
                    ancestorLink.resolve("vendor.gpg"), tempDirectory, owner))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no-follow ancestry");

    Path safeParent = Files.createDirectory(tempDirectory.resolve("safe"));
    Path target = Files.writeString(safeParent.resolve("target"), "original");
    Path destination = Files.createSymbolicLink(safeParent.resolve("vendor.gpg"), target);

    assertThatThrownBy(
            () -> TrustedDestinationAncestry.requireSafe(destination, tempDirectory, owner))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no-follow ancestry");
  }
}
