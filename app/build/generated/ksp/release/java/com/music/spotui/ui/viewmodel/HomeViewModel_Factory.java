package com.music.spotui.ui.viewmodel;

import com.music.spotui.ui.repository.AppRepository;
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
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<AppRepository> repositoryProvider;

  private HomeViewModel_Factory(Provider<AppRepository> repositoryProvider) {
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(repositoryProvider.get());
  }

  public static HomeViewModel_Factory create(Provider<AppRepository> repositoryProvider) {
    return new HomeViewModel_Factory(repositoryProvider);
  }

  public static HomeViewModel newInstance(AppRepository repository) {
    return new HomeViewModel(repository);
  }
}
