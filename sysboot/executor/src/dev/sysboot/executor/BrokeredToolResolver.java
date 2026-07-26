package dev.sysboot.executor;

import dev.sysboot.core.ToolSpec;
import java.nio.file.Path;

/** {@link ToolResolver} backed by the real {@link ToolBroker}. */
final class BrokeredToolResolver implements ToolResolver {

  private final ToolBroker broker;

  BrokeredToolResolver(ToolBroker broker) {
    this.broker = broker;
  }

  @Override
  public Path resolve(ToolSpec spec) {
    return broker.resolve(spec);
  }
}
