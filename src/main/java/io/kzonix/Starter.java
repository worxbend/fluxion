package io.kzonix;

import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = { InitializingModule.class })
public interface Starter {
  ApplicationStarterEntryPoint entryPoint();
}
