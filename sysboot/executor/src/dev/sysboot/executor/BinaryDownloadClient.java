package dev.sysboot.executor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

interface BinaryDownloadClient {

  void downloadToFile(URI url, Path destination) throws IOException;

  String downloadText(URI url) throws IOException;
}
