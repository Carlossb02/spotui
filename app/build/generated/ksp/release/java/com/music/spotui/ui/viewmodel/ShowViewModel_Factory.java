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
public final class ShowViewModel_Factory implements Factory<ShowViewModel> {
  private final Provider<AppRepository> repositoryProvider;

  private final Provider<CurrentSongState> currentSongStateProvider;

  private ShowViewModel_Factory(Provider<AppRepository> repositoryProvider,
      Provider<CurrentSongState> currentSongStateProvider) {
    this.repositoryProvider = repositoryProvider;
    this.currentSongStateProvider = currentSongStateProvider;
  }

  @Override
  public ShowViewModel get() {
    return newInstance(repositoryProvider.get(), currentSongStateProvider.get());
  }

  public static ShowViewModel_Factory create(Provider<AppRepository> repositoryProvider,
      Provider<CurrentSongState> currentSongStateProvider) {
    return new ShowViewModel_Factory(repositoryProvider, currentSongStateProvider);
  }

  public static ShowViewModel newInstance(AppRepository repository,
      CurrentSongState currentSongState) {
    return new ShowViewModel(repository, currentSongState);
  }
}
