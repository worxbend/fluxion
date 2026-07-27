package dev.sysboot.executor;

import dev.sysboot.core.ToolSpec;
import java.nio.file.Path;

/** Seam between an executor and {@link ToolBroker}, so tests can supply a stub binary. */
@FunctionalInterface
interface ToolResolver {

  Path resolve(ToolSpec spec);
}
