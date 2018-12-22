package io.kzonix.configuration;

public interface ConfigurationProvider<T extends ConfigurationProps> {

  T provide();
}
