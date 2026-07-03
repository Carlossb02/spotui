package com.music.spotui.ui.viewmodel;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
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
public final class LyricsViewModel_Factory implements Factory<LyricsViewModel> {
  @Override
  public LyricsViewModel get() {
    return newInstance();
  }

  public static LyricsViewModel_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static LyricsViewModel newInstance() {
    return new LyricsViewModel();
  }

  private static final class InstanceHolder {
    static final LyricsViewModel_Factory INSTANCE = new LyricsViewModel_Factory();
  }
}
