package dev.sysboot.app;

import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.SudoPasswordProvider;
import dev.sysboot.tui.TuiExecutionEventListener;
import dev.sysboot.tui.TuiSudoPasswordProvider;

public final class TuiOutputAdapter implements OutputAdapter {

  private final TuiExecutionEventListener eventListener;
  private final TuiSudoPasswordProvider sudoPasswordProvider;

  public TuiOutputAdapter() {
    this.eventListener = new TuiExecutionEventListener();
    this.sudoPasswordProvider = new TuiSudoPasswordProvider();
  }

  @Override
  public ExecutionEventListener eventListener() {
    return eventListener;
  }

  @Override
  public SudoPasswordProvider sudoPasswordProvider() {
    return sudoPasswordProvider;
  }

  public TuiExecutionEventListener tuiEventListener() {
    return eventListener;
  }

  public TuiSudoPasswordProvider tuiSudoProvider() {
    return sudoPasswordProvider;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {}
}
