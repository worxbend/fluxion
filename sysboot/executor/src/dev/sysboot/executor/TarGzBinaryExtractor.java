package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.Sha256Digest;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class TarGzBinaryExtractor {

  static final long MAX_EXPANDED_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L;
  static final long MAX_EXTRACTED_ENTRY_BYTES = 1024L * 1024L * 1024L;

  private final BinaryFileSystem fileSystem;
  private final long maxExpandedArchiveBytes;
  private final long maxExtractedEntryBytes;

  TarGzBinaryExtractor(BinaryFileSystem fileSystem) {
    this(fileSystem, MAX_EXPANDED_ARCHIVE_BYTES, MAX_EXTRACTED_ENTRY_BYTES);
  }

  TarGzBinaryExtractor(
      BinaryFileSystem fileSystem, long maxExpandedArchiveBytes, long maxExtractedEntryBytes) {
    this.fileSystem = fileSystem;
    this.maxExpandedArchiveBytes = requirePositive(maxExpandedArchiveBytes);
    this.maxExtractedEntryBytes = requirePositive(maxExtractedEntryBytes);
  }

  ExtractedBinary extract(Path archive, CompiledBinaryModule module) throws IOException {
    try (InputStream fileInput = fileSystem.openInput(archive);
        var buffered = new BufferedInputStream(fileInput);
        var gzip = new GzipCompressorInputStream(buffered);
        var budgeted = new ExpandedArchiveBudgetInputStream(gzip, maxExpandedArchiveBytes);
        var tar = new TarArchiveInputStream(budgeted)) {
      return findAndExtract(tar, module);
    }
  }

  private ExtractedBinary findAndExtract(TarArchiveInputStream tar, CompiledBinaryModule module)
      throws IOException {
    long expandedBytes = 0;
    ExtractedBinary extracted = null;
    try {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        expandedBytes = checkedExpandedSize(expandedBytes, entry);
        if (entry.isFile() && archiveEntryMatches(entry.getName(), module)) {
          if (extracted != null) {
            throw ambiguousSelection(module);
          }
          extracted = extractEntry(tar, module);
        }
      }
      if (extracted == null) {
        throw new IOException("Binary '" + module.binaryName() + "' not found in archive");
      }
      return extracted;
    } catch (IOException | RuntimeException failure) {
      deletePartial(extracted == null ? null : extracted.path(), failure);
      throw failure;
    }
  }

  private long checkedExpandedSize(long expandedBytes, TarArchiveEntry entry) throws IOException {
    long entryBytes = entry.getRealSize();
    if (entryBytes < 0) {
      throw new IOException("Archive entry has an unknown expanded size: " + entry.getName());
    }
    if (entryBytes > maxExtractedEntryBytes) {
      throw new IOException(
          "Archive entry exceeds maximum size of " + maxExtractedEntryBytes + " bytes");
    }
    if (expandedBytes > maxExpandedArchiveBytes - entryBytes) {
      throw new IOException(
          "Expanded archive exceeds maximum size of " + maxExpandedArchiveBytes + " bytes");
    }
    return expandedBytes + entryBytes;
  }

  private IOException ambiguousSelection(CompiledBinaryModule module) {
    return new IOException(
        "Archive member selection is ambiguous for " + module.archivePath().orElseThrow());
  }

  private ExtractedBinary extractEntry(TarArchiveInputStream tar, CompiledBinaryModule module)
      throws IOException {
    Path extracted = fileSystem.createTempFile("sysboot-extracted-", "-" + module.binaryName());
    try {
      Sha256Digest digest = fileSystem.copyAndDigest(tar, extracted, maxExtractedEntryBytes);
      return new ExtractedBinary(extracted, digest);
    } catch (IOException | RuntimeException failure) {
      deletePartial(extracted, failure);
      throw failure;
    }
  }

  private void deletePartial(Path extracted, Throwable failure) {
    if (extracted == null) {
      return;
    }
    try {
      fileSystem.deleteIfExists(extracted);
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private boolean archiveEntryMatches(String entryName, CompiledBinaryModule module) {
    String stripped = stripComponents(entryName, module.stripComponents());
    if (stripped.isBlank()) {
      return false;
    }
    return stripped.equals(module.archivePath().orElseThrow());
  }

  private String stripComponents(String entryName, int count) {
    String[] components = entryName.split("/");
    if (components.length <= count) {
      return "";
    }
    return String.join("/", Arrays.copyOfRange(components, count, components.length));
  }

  private long requirePositive(long value) {
    if (value <= 0) {
      throw new IllegalArgumentException("Extraction limits must be positive");
    }
    return value;
  }

  record ExtractedBinary(Path path, Sha256Digest digest) {}

  /**
   * Counts the complete decompressed TAR stream, including headers, padding, GNU long-name records,
   * and PAX metadata that Commons Compress consumes before returning an entry.
   */
  private static final class ExpandedArchiveBudgetInputStream extends FilterInputStream {

    private final long maximumBytes;
    private long consumedBytes;

    ExpandedArchiveBudgetInputStream(InputStream input, long maximumBytes) {
      super(input);
      this.maximumBytes = maximumBytes;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        consume(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, boundedLength(length));
      if (read > 0) {
        consume(read);
      }
      return read;
    }

    @Override
    public long skip(long bytes) throws IOException {
      long skipped = super.skip(Math.min(bytes, remainingPlusOne()));
      if (skipped > 0) {
        consume(skipped);
      }
      return skipped;
    }

    private int boundedLength(int requested) {
      return (int) Math.min(requested, Math.min(Integer.MAX_VALUE, remainingPlusOne()));
    }

    private long remainingPlusOne() {
      long remaining = maximumBytes - consumedBytes;
      return remaining == Long.MAX_VALUE ? Long.MAX_VALUE : remaining + 1;
    }

    private void consume(long bytes) throws IOException {
      if (consumedBytes > maximumBytes - bytes) {
        throw new IOException(
            "Expanded archive exceeds maximum size of " + maximumBytes + " bytes");
      }
      consumedBytes += bytes;
    }
  }
}
