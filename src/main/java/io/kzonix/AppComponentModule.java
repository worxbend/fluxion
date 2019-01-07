package io.kzonix;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import javax.inject.Singleton;

@Module
abstract class AppComponentModule {
  @Binds
  @Singleton @IntoSet
  abstract ApplicationComponent provideMainApplicationComponent(MainApplicationComponentImpl pump);
  @Binds
  @Singleton @IntoSet
  abstract ApplicationComponent provideBackgroundWorkerComponent(BackgroundWorkerComponentImpl pump);
  @Binds
  @Singleton @IntoSet
  abstract ApplicationComponent provideAnonymousStatisticComponent(AnonymousStatisticComponentImpl pump);
}