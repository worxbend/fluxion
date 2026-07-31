package dev.sysboot.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class BinaryDigest {

  private static final int BUFFER_BYTES = 64 * 1024;

  private BinaryDigest() {}

  static String hex(BinaryFileSystem fileSystem, Path path, String algorithm, long maximumBytes)
      throws IOException, NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    byte[] buffer = new byte[BUFFER_BYTES];
    long hashedBytes = 0;
    try (InputStream input = fileSystem.openInput(path)) {
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        hashedBytes += read;
        if (hashedBytes > maximumBytes) {
          throw new IOException(
              "Downloaded artifact exceeds maximum hash size of " + maximumBytes + " bytes");
        }
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
