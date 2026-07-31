package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TarGzBinaryExtractorTest {

  @TempDir Path tempDir;

  @Test
  void extract_whenSelectedEntryExceedsLimit_rejectsBeforeCreatingOutput() throws Exception {
    Path archive = archive(entries("release/bin/rg", "12345"));
    var extractor = new TarGzBinaryExtractor(new DefaultBinaryFileSystem(), 10_000, 4);

    assertThatThrownBy(() -> extractor.extract(archive, module()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("entry exceeds maximum size");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  @Test
  void extract_whenExpandedArchiveExceedsLimit_rejectsBeforeExtraction() throws Exception {
    var contents = entries("release/share/data", "123");
    contents.put("release/bin/rg", "456");
    Path archive = archive(contents);
    var extractor = new TarGzBinaryExtractor(new DefaultBinaryFileSystem(), 5, 4);

    assertThatThrownBy(() -> extractor.extract(archive, module()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Expanded archive exceeds maximum size");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  @Test
  void extract_whenBoundedCopyFails_deletesPartialExtraction() throws Exception {
    Path archive = archive(entries("release/bin/rg", "binary"));
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doAnswer(
            invocation ->
                Files.createTempFile(
                    tempDir,
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class)))
        .when(fileSystem)
        .createTempFile(anyString(), anyString());
    doAnswer(
            invocation -> {
              Files.writeString(invocation.getArgument(1, Path.class), "partial");
              throw new IOException("disk full");
            })
        .when(fileSystem)
        .copyAndDigest(any(InputStream.class), any(Path.class), anyLong());
    var extractor = new TarGzBinaryExtractor(fileSystem);

    assertThatThrownBy(() -> extractor.extract(archive, module()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("disk full");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  @ParameterizedTest
  @ValueSource(ints = {TarArchiveOutputStream.LONGFILE_GNU, TarArchiveOutputStream.LONGFILE_POSIX})
  void extract_whenLongNameMetadataExceedsTinyBudget_rejectsWithoutOutput(int longFileMode)
      throws Exception {
    String longName = "a".repeat(4_096) + "/rg";
    Path archive = archive(entries(longName, "x"), longFileMode);
    var extractor = new TarGzBinaryExtractor(new DefaultBinaryFileSystem(), 10, 10);

    assertThatThrownBy(() -> extractor.extract(archive, module()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Expanded archive exceeds maximum size");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  @Test
  void extract_whenStrippedArchivePathMatchesMultipleMembers_rejects() throws Exception {
    var contents = entries("first/bin/rg", "first");
    contents.put("second/bin/rg", "second");
    Path archive = archive(contents);

    assertThatThrownBy(
            () ->
                new TarGzBinaryExtractor(new DefaultBinaryFileSystem()).extract(archive, module()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("ambiguous");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  @Test
  void extract_whenArchivePathOnlyMatchesBeforeStripping_rejects() throws Exception {
    Path archive = archive(entries("release/bin/rg", "binary"));
    var configured = module();
    var rawPathSelector =
        new CompiledBinaryModule(
            configured.name(),
            configured.binaryName(),
            configured.url(),
            configured.checksum(),
            configured.checksumUrl(),
            configured.signatureUrl(),
            configured.installPath(),
            Optional.of("release/bin/rg"),
            1,
            configured.installMode(),
            configured.symlinkPath(),
            configured.continueOnError(),
            configured.versionCommand(),
            configured.expectedVersion(),
            configured.allowedSignerFingerprint());

    assertThatThrownBy(
            () ->
                new TarGzBinaryExtractor(new DefaultBinaryFileSystem())
                    .extract(archive, rawPathSelector))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not found");
    assertThat(directoryEntries()).containsExactly(archive);
  }

  private Path archive(Map<String, String> contents) throws IOException {
    return archive(contents, TarArchiveOutputStream.LONGFILE_ERROR);
  }

  private Path archive(Map<String, String> contents, int longFileMode) throws IOException {
    Path archive = tempDir.resolve("rg.tar.gz");
    Files.write(archive, tarGz(contents, longFileMode));
    return archive;
  }

  private byte[] tarGz(Map<String, String> contents, int longFileMode) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(output);
        var tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(longFileMode);
      for (var content : contents.entrySet()) {
        byte[] body = content.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entry = new TarArchiveEntry(content.getKey());
        entry.setSize(body.length);
        tar.putArchiveEntry(entry);
        tar.write(body);
        tar.closeArchiveEntry();
      }
    }
    return output.toByteArray();
  }

  private LinkedHashMap<String, String> entries(String name, String contents) {
    var entries = new LinkedHashMap<String, String>();
    entries.put(name, contents);
    return entries;
  }

  private java.util.List<Path> directoryEntries() throws IOException {
    try (var entries = Files.list(tempDir)) {
      return entries.toList();
    }
  }

  private CompiledBinaryModule module() {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create("https://example.test/rg.tar.gz")),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Path.of("/usr/local/bin/rg"),
        Optional.of("bin/rg"),
        1,
        Optional.of("0755"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty());
  }
}
