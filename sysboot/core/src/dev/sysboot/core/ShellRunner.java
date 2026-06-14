package dev.sysboot.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface ShellRunner {
  ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);
}
