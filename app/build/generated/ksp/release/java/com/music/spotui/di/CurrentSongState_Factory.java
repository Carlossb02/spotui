package com.music.spotui.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class CurrentSongState_Factory implements Factory<CurrentSongState> {
  @Override
  public CurrentSongState get() {
    return newInstance();
  }

  public static CurrentSongState_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static CurrentSongState newInstance() {
    return new CurrentSongState();
  }

  private static final class InstanceHolder {
    static final CurrentSongState_Factory INSTANCE = new CurrentSongState_Factory();
  }
}
