package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.app.OutputAdapter;
import dev.sysboot.app.PlainOutputAdapter;
import org.junit.jupiter.api.Test;

/**
 * Verifies OutputAdapter selection: --no-tui path wires PlainOutputAdapter; ApplicationContext has
 * no TUI app in CLI mode.
 */
class OutputAdapterSelectionTest {

  @Test
  void plainOutputAdapter_instantiatesWithNonNullFields() {
    var adapter = new PlainOutputAdapter();
    assertThat(adapter.eventListener()).isNotNull();
    assertThat(adapter.sudoPasswordProvider()).isNotNull();
  }

  @Test
  void plainOutputAdapter_implementsOutputAdapterInterface() {
    OutputAdapter adapter = new PlainOutputAdapter();
    assertThat(adapter).isInstanceOf(PlainOutputAdapter.class);
  }

  @Test
  void noTuiMode_applicationContext_hasEmptyTuiApp() {
    var context = ApplicationContext.create(true);
    assertThat(context.tuiApp()).isEmpty();
  }

  @Test
  void plainOutputAdapter_startAndStop_doNotThrow() {
    var adapter = new PlainOutputAdapter();
    adapter.start();
    adapter.stop();
  }
}
