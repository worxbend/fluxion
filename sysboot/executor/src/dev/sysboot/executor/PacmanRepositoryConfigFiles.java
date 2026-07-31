package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Path;

interface PacmanRepositoryConfigFiles {

  String readTrusted(Path configPath) throws IOException;

  Path stage(String content) throws IOException;

  void deleteIfExists(Path path) throws IOException;
}
