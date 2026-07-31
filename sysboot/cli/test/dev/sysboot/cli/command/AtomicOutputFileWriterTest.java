package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicOutputFileWriterTest {

  @Test
  void write_withForce_replacesSymlinkEntryWithoutChangingReferent(@TempDir Path directory)
      throws Exception {
    Path referent = Files.writeString(directory.resolve("referent"), "original");
    Path output = directory.resolve("output");
    Files.createSymbolicLink(output, referent);

    AtomicOutputFileWriter.write(output, "generated", true);

    assertThat(output).isRegularFile();
    assertThat(Files.readString(output)).isEqualTo("generated");
    assertThat(Files.readString(referent)).isEqualTo("original");
  }

  @Test
  void write_withoutForce_rejectsDanglingSymlinkAndPreservesReferent(@TempDir Path directory)
      throws Exception {
    Path referent = directory.resolve("missing");
    Path output = directory.resolve("output");
    Files.createSymbolicLink(output, referent);

    assertThatThrownBy(() -> AtomicOutputFileWriter.write(output, "generated", false))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(referent).doesNotExist();
  }

  @Test
  void write_rejectsSymlinkAncestorBeforeCreatingChildren(@TempDir Path directory)
      throws Exception {
    Path referent = Files.createDirectory(directory.resolve("referent"));
    Path link = directory.resolve("link");
    Files.createSymbolicLink(link, referent);

    assertThatThrownBy(
            () -> AtomicOutputFileWriter.write(link.resolve("created/output"), "generated", true))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("symbolic links");
    assertThat(referent.resolve("created")).doesNotExist();
  }
}
