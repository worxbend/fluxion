package dev.sysboot.core;

@FunctionalInterface
public interface ExecutionEventListener {
  void onEvent(ExecutionEvent event);
}
