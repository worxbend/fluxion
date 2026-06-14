package dev.sysboot.app;

import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.SudoPasswordProvider;

public interface OutputAdapter {

  ExecutionEventListener eventListener();

  SudoPasswordProvider sudoPasswordProvider();

  void start();

  void stop();
}
