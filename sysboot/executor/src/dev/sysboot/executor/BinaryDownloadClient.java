package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

interface BinaryDownloadClient {

  void downloadToFile(URI url, Path destination) throws IOException;

  default Sha256Digest downloadToFileWithDigest(URI url, Path destination) throws IOException {
    downloadToFile(url, destination);
    return ArtifactDigests.sha256(destination, HttpBinaryDownloadClient.MAX_FILE_BYTES);
  }

  String downloadText(URI url) throws IOException;
}
