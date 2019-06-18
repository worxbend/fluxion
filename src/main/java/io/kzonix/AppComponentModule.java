package io.kzonix;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import javax.inject.Singleton;

@Module
public abstract class AppComponentModule {

  @Binds
  @Singleton
  @IntoSet
  public abstract ApplicationComponent provideMainApplicationComponent(MainApplicationComponentImpl pump);

  @Binds
  @Singleton
  @IntoSet
  public abstract ApplicationComponent provideBackgroundWorkerComponent(BackgroundWorkerComponentImpl pump);

  @Binds
  @Singleton
  @IntoSet
  public abstract ApplicationComponent provideAnonymousStatisticComponent(AnonymousStatisticComponentImpl pump);
}