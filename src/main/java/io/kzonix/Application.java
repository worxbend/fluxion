package io.kzonix;

import java.util.logging.Logger;

public class Application {

  private static final Logger log = Logger.getAnonymousLogger();

  public static void main(String... args) {
    Starter starter = DaggerStarter.builder().build();
    starter.entryPoint().start();
  }
}
