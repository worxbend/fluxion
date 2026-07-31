package dev.sysboot.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

final class OpenPgpKeyDecoder {

  private static final String BEGIN = "-----BEGIN PGP PUBLIC KEY BLOCK-----";
  private static final String END = "-----END PGP PUBLIC KEY BLOCK-----";

  private OpenPgpKeyDecoder() {}

  static byte[] decode(Path source, long maximumBytes) throws IOException {
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OpenPGP key stage must be a regular non-symlink file");
    }
    if (Files.size(source) > maximumBytes) {
      throw new IOException("OpenPGP key exceeds " + maximumBytes + " bytes");
    }
    byte[] content = Files.readAllBytes(source);
    if (!startsWith(content, BEGIN)) {
      return content;
    }
    return decodeArmor(content);
  }

  private static byte[] decodeArmor(byte[] content) throws IOException {
    String armored = new String(content, StandardCharsets.US_ASCII);
    if (!java.util.Arrays.equals(armored.getBytes(StandardCharsets.US_ASCII), content)) {
      throw new IOException("ASCII-armored OpenPGP key contains non-ASCII bytes");
    }
    List<String> lines = armored.lines().toList();
    int last = lastNonBlank(lines);
    if (lines.isEmpty()
        || !BEGIN.equals(lines.getFirst())
        || last < 2
        || !END.equals(lines.get(last))) {
      throw new IOException("Malformed ASCII-armored OpenPGP public key");
    }
    int dataStart = dataStart(lines, last);
    var encoded = new StringBuilder();
    String checksum = null;
    for (int index = dataStart; index < last; index++) {
      String line = lines.get(index).strip();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("=")) {
        if (checksum != null || !line.matches("=[A-Za-z0-9+/]{4}")) {
          throw new IOException("Malformed ASCII-armored OpenPGP checksum");
        }
        checksum = line.substring(1);
      } else {
        if (checksum != null || !line.matches("[A-Za-z0-9+/]+={0,2}")) {
          throw new IOException("Malformed ASCII-armored OpenPGP payload");
        }
        encoded.append(line);
      }
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded.toString());
      if (checksum != null) {
        requireCrc(decoded, checksum);
      }
      return decoded;
    } catch (IllegalArgumentException e) {
      throw new IOException("Malformed ASCII-armored OpenPGP payload", e);
    }
  }

  private static int dataStart(List<String> lines, int last) throws IOException {
    for (int index = 1; index < last; index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        return index + 1;
      }
      if (!line.matches("[A-Za-z][A-Za-z0-9-]*:.*")) {
        return 1;
      }
    }
    throw new IOException("ASCII-armored OpenPGP key has no payload");
  }

  private static void requireCrc(byte[] decoded, String encodedChecksum) throws IOException {
    byte[] expected = Base64.getDecoder().decode(encodedChecksum);
    int crc = 0xB704CE;
    for (byte value : decoded) {
      crc ^= (value & 0xff) << 16;
      for (int bit = 0; bit < 8; bit++) {
        crc <<= 1;
        if ((crc & 0x1000000) != 0) {
          crc ^= 0x1864CFB;
        }
      }
    }
    byte[] actual = {(byte) (crc >> 16), (byte) (crc >> 8), (byte) crc};
    if (!java.util.Arrays.equals(expected, actual)) {
      throw new IOException("ASCII-armored OpenPGP checksum mismatch");
    }
  }

  private static int lastNonBlank(List<String> lines) {
    int index = lines.size() - 1;
    while (index >= 0 && lines.get(index).isBlank()) {
      index--;
    }
    return index;
  }

  private static boolean startsWith(byte[] content, String prefix) {
    byte[] expected = prefix.getBytes(StandardCharsets.US_ASCII);
    if (content.length < expected.length) {
      return false;
    }
    for (int index = 0; index < expected.length; index++) {
      if (content[index] != expected[index]) {
        return false;
      }
    }
    return true;
  }
}
