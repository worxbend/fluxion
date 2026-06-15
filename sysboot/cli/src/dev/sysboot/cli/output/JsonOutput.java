package dev.sysboot.cli.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import java.io.PrintWriter;

public final class JsonOutput {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonOutput() {}

  public static void write(PrintWriter out, Object value) {
    out.println(toJson(value));
  }

  public static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new CliFailureException(ExitCode.GENERAL_FAILURE, "Could not render JSON output", e);
    }
  }
}
