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
public final class CategoryViewModel_Factory implements Factory<CategoryViewModel> {
  private final Provider<AppRepository> repositoryProvider;

  private CategoryViewModel_Factory(Provider<AppRepository> repositoryProvider) {
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public CategoryViewModel get() {
    return newInstance(repositoryProvider.get());
  }

  public static CategoryViewModel_Factory create(Provider<AppRepository> repositoryProvider) {
    return new CategoryViewModel_Factory(repositoryProvider);
  }

  public static CategoryViewModel newInstance(AppRepository repository) {
    return new CategoryViewModel(repository);
  }
}
