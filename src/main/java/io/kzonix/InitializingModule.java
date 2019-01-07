package io.kzonix;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module(includes = AppComponentModule.class)
class InitializingModule {
  @Provides
  @Singleton
  Initializer provideHeater() {
    return new RootInitializer();
  }
}