package io.kzonix;

import javax.inject.Inject;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class BackgroundWorkerComponentImpl implements ApplicationComponent {

  @Inject
  public BackgroundWorkerComponentImpl() {
    // TODO: inject necessary dependencies (Dagger 2 require inject constructor for bindings configuration)
  }

  @Override
  public void start() {
    log.info("Start '{}'", this.getClass().getSimpleName());
  }
}
