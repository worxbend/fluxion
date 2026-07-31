package dev.sysboot.core;

import java.util.Objects;

/** Authorizes an item whose configuration requires explicit confirmation. */
@FunctionalInterface
public interface ExecutionApproval {

  boolean approve(ConfirmationRequest request);

  static ExecutionApproval denyAll() {
    return request -> false;
  }

  static ExecutionApproval approveAll() {
    return request -> true;
  }

  record ConfirmationRequest(String item, String prompt) {

    public ConfirmationRequest {
      Objects.requireNonNull(item);
      Objects.requireNonNull(prompt);
      if (item.isBlank() || prompt.isBlank()) {
        throw new IllegalArgumentException("confirmation item and prompt must not be blank");
      }
    }
  }
}
