package com.music.spotui.ui.notification;

import com.music.spotui.di.CurrentSongState;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

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
public final class PlaybackService_MembersInjector implements MembersInjector<PlaybackService> {
  private final Provider<CurrentSongState> currentSongStateProvider;

  private PlaybackService_MembersInjector(Provider<CurrentSongState> currentSongStateProvider) {
    this.currentSongStateProvider = currentSongStateProvider;
  }

  @Override
  public void injectMembers(PlaybackService instance) {
    injectCurrentSongState(instance, currentSongStateProvider.get());
  }

  public static MembersInjector<PlaybackService> create(
      Provider<CurrentSongState> currentSongStateProvider) {
    return new PlaybackService_MembersInjector(currentSongStateProvider);
  }

  @InjectedFieldSignature("com.music.spotui.ui.notification.PlaybackService.currentSongState")
  public static void injectCurrentSongState(PlaybackService instance,
      CurrentSongState currentSongState) {
    instance.currentSongState = currentSongState;
  }
}
