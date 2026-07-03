package com.music.spotui.ui.repository;

import com.music.spotui.data.api.Api;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class AppRepository_Factory implements Factory<AppRepository> {
  private final Provider<Api> apiProvider;

  private AppRepository_Factory(Provider<Api> apiProvider) {
    this.apiProvider = apiProvider;
  }

  @Override
  public AppRepository get() {
    return newInstance(apiProvider.get());
  }

  public static AppRepository_Factory create(Provider<Api> apiProvider) {
    return new AppRepository_Factory(apiProvider);
  }

  public static AppRepository newInstance(Api api) {
    return new AppRepository(api);
  }
}
