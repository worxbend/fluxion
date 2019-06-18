package io.kzonix;

import dagger.Lazy;
import java.util.Set;
import javax.inject.Inject;

public class ApplicationStarterEntryPoint {
  private final Lazy<Initializer> initializerLazy; // Create a possibly costly instance only when we use it.
  private final Set<ApplicationComponent> applicationComponents;

  @Inject
  ApplicationStarterEntryPoint(Lazy<Initializer> initializerLazy, Set<ApplicationComponent> applicationComponents) {
    this.initializerLazy = initializerLazy;
    this.applicationComponents = applicationComponents;
  }

  public void start() {
    final Initializer initializer = this.initializerLazy.get();
    initializer.applyConfiguration();
    initializer.initCache();
    initializer.initDaemon();
    for (ApplicationComponent applicationComponent : applicationComponents) {
      applicationComponent.start();
    }
  }
}
