package com.music.spotui.ui.viewmodel;

import com.music.spotui.di.CurrentSongState;
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
public final class SearchViewModel_Factory implements Factory<SearchViewModel> {
  private final Provider<AppRepository> repositoryProvider;

  private final Provider<CurrentSongState> currentSongStateProvider;

  private SearchViewModel_Factory(Provider<AppRepository> repositoryProvider,
      Provider<CurrentSongState> currentSongStateProvider) {
    this.repositoryProvider = repositoryProvider;
    this.currentSongStateProvider = currentSongStateProvider;
  }

  @Override
  public SearchViewModel get() {
    return newInstance(repositoryProvider.get(), currentSongStateProvider.get());
  }

  public static SearchViewModel_Factory create(Provider<AppRepository> repositoryProvider,
      Provider<CurrentSongState> currentSongStateProvider) {
    return new SearchViewModel_Factory(repositoryProvider, currentSongStateProvider);
  }

  public static SearchViewModel newInstance(AppRepository repository,
      CurrentSongState currentSongState) {
    return new SearchViewModel(repository, currentSongState);
  }
}
