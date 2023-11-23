package com.android.server.appsearch;

import android.annotation.NonNull;
import android.content.Context;
import android.os.UserHandle;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** An interface which exposes environment specific methods for AppSearch. */
public interface AppSearchEnvironment {

  /** Returns the directory to initialize appsearch based on the environment. */
  public File getAppSearchDir(@NonNull Context context, @NonNull UserHandle userHandle);

  /** Returns the correct context for the user based on the environment. */
  public Context createContextAsUser(@NonNull Context context, @NonNull UserHandle userHandle);

  /** Returns an ExecutorService based on given parameters. */
  public ExecutorService createExecutorService(
      int corePoolSize,
      int maxConcurrency,
      long keepAliveTime,
      TimeUnit unit,
      BlockingQueue<Runnable> workQueue,
      int priority);

  /** Returns an ExecutorService with a single thread. */
  public ExecutorService createSingleThreadExecutor();
}

