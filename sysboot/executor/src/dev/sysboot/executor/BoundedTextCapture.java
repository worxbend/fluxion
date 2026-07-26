package dev.sysboot.executor;

/**
 * Captures process output without letting a chatty child exhaust memory.
 *
 * <p>The head is kept verbatim because the first lines usually explain a failure, and the tail is
 * kept because the last lines usually explain a hang. Everything between them is dropped and
 * replaced by a marker so the omission is visible rather than silent.
 */
final class BoundedTextCapture {

  private static final int DEFAULT_HEAD_LIMIT = 256 * 1024;
  private static final int DEFAULT_TAIL_LIMIT = 256 * 1024;

  private final StringBuilder head;
  private final int headLimit;
  private final char[] tail;
  private int tailStart;
  private int tailLength;
  private long omitted;

  BoundedTextCapture() {
    this(DEFAULT_HEAD_LIMIT, DEFAULT_TAIL_LIMIT);
  }

  BoundedTextCapture(int headLimit, int tailLimit) {
    if (headLimit < 0 || tailLimit < 1) {
      throw new IllegalArgumentException("headLimit must be >= 0 and tailLimit >= 1");
    }
    this.head = new StringBuilder(Math.min(headLimit, 8192));
    this.headLimit = headLimit;
    this.tail = new char[tailLimit];
  }

  void append(char[] buffer, int offset, int length) {
    int index = offset;
    int remaining = length;
    int headRoom = headLimit - head.length();
    if (headRoom > 0) {
      int copied = Math.min(headRoom, remaining);
      head.append(buffer, index, copied);
      index += copied;
      remaining -= copied;
    }
    for (int i = 0; i < remaining; i++) {
      appendToTail(buffer[index + i]);
    }
  }

  private void appendToTail(char value) {
    if (tailLength < tail.length) {
      tail[(tailStart + tailLength) % tail.length] = value;
      tailLength++;
      return;
    }
    tail[tailStart] = value;
    tailStart = (tailStart + 1) % tail.length;
    omitted++;
  }

  @Override
  public String toString() {
    if (tailLength == 0) {
      return head.toString();
    }
    var result = new StringBuilder(head.length() + tailLength + 64);
    result.append(head);
    if (omitted > 0) {
      result
          .append(System.lineSeparator())
          .append("... [")
          .append(omitted)
          .append(" characters omitted] ...")
          .append(System.lineSeparator());
    }
    for (int i = 0; i < tailLength; i++) {
      result.append(tail[(tailStart + i) % tail.length]);
    }
    return result.toString();
  }
}
