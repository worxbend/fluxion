package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultBinaryFileSystemTest {

  @TempDir Path tempDir;

  private final DefaultBinaryFileSystem fileSystem = new DefaultBinaryFileSystem();

  @Test
  void pathEntryExists_whenSymlinkTargetIsMissing_stillFindsLinkEntry() throws Exception {
    Path link = tempDir.resolve("dangling");
    Files.createSymbolicLink(link, tempDir.resolve("missing"));

    assertThat(fileSystem.pathEntryExists(link)).isTrue();
    assertThat(fileSystem.isSymbolicLink(link)).isTrue();
    assertThat(fileSystem.isRegularFile(link)).isFalse();
  }

  @Test
  void securePrivilegedDirectory_whenDirectoryIsWorldWritable_refusesIt() {
    assertThat(fileSystem.isSecurePrivilegedDirectory(Path.of("/tmp"))).isFalse();
  }

  @Test
  void securePrivilegedDirectory_whenAncestorIsSymlink_refusesIt() throws Exception {
    Path real = Files.createDirectory(tempDir.resolve("real"));
    Path link = tempDir.resolve("link");
    Files.createSymbolicLink(link, real);

    assertThat(fileSystem.isSecurePrivilegedDirectory(link)).isFalse();
  }
}
