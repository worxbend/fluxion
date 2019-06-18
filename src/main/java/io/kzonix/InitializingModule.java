package io.kzonix;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module(includes = {
    AppComponentModule.class,
    RootLoaderModule.class,
    CommandLineModule.class
})
class InitializingModule {
  @Provides
  @Singleton
  Initializer provideRootInitializer() {
    return new RootInitializer();
  }
}