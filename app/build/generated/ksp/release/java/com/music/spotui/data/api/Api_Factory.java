package com.music.spotui.data.api;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class Api_Factory implements Factory<Api> {
  private final Provider<Context> contextProvider;

  private Api_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public Api get() {
    return newInstance(contextProvider.get());
  }

  public static Api_Factory create(Provider<Context> contextProvider) {
    return new Api_Factory(contextProvider);
  }

  public static Api newInstance(Context context) {
    return new Api(context);
  }
}
