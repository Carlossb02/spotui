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
public final class PlayerViewModel_Factory implements Factory<PlayerViewModel> {
  private final Provider<CurrentSongState> currentSongStateProvider;

  private final Provider<AppRepository> repositoryProvider;

  private PlayerViewModel_Factory(Provider<CurrentSongState> currentSongStateProvider,
      Provider<AppRepository> repositoryProvider) {
    this.currentSongStateProvider = currentSongStateProvider;
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public PlayerViewModel get() {
    return newInstance(currentSongStateProvider.get(), repositoryProvider.get());
  }

  public static PlayerViewModel_Factory create(Provider<CurrentSongState> currentSongStateProvider,
      Provider<AppRepository> repositoryProvider) {
    return new PlayerViewModel_Factory(currentSongStateProvider, repositoryProvider);
  }

  public static PlayerViewModel newInstance(CurrentSongState currentSongState,
      AppRepository repository) {
    return new PlayerViewModel(currentSongState, repository);
  }
}
