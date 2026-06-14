package dev.sysboot.executor;

public final class CyclicDependencyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CyclicDependencyException(String message) {
    super(message);
  }
}
