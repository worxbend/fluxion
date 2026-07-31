package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

interface ScriptDownloadClient {

  Path download(URI url, Sha256Digest sha256) throws IOException;
}
