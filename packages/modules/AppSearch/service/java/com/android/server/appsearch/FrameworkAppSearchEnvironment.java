package com.android.server.appsearch;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Environment;
import android.os.UserHandle;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/** Contains utility methods for Framework implementation of AppSearch. */
public class FrameworkAppSearchEnvironment implements AppSearchEnvironment {

  /**
   * Returns AppSearch directory in the credential encrypted system directory for the given user.
   *
   * <p>This folder should only be accessed after unlock.
   */
  @Override
  public File getAppSearchDir(@NonNull Context unused, @NonNull UserHandle userHandle) {
    // Duplicates the implementation of Environment#getDataSystemCeDirectory
    // TODO(b/191059409): Unhide Environment#getDataSystemCeDirectory and switch to it.
    Objects.requireNonNull(userHandle);
    File systemCeDir = new File(Environment.getDataDirectory(), "system_ce");
    File systemCeUserDir = new File(systemCeDir, String.valueOf(userHandle.getIdentifier()));
    return new File(systemCeUserDir, "appsearch");
  }

  /** Creates context for the user based on the userHandle. */
  @Override
  public Context createContextAsUser(@NonNull Context context, @NonNull UserHandle userHandle) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(userHandle);
    return context.createContextAsUser(userHandle, /*flags=*/ 0);
  }

  /** Creates and returns a ThreadPoolExecutor for given parameters. */
  @Override
  public ExecutorService createExecutorService(
      int corePoolSize,
      int maxConcurrency,
      long keepAliveTime,
      TimeUnit unit,
      BlockingQueue<Runnable> workQueue,
      int priority) {
    return new ThreadPoolExecutor(
        corePoolSize,
        maxConcurrency,
        keepAliveTime,
        unit,
        workQueue);
  }

  /** Createsand returns an ExecutorService with a single thread. */
  @Override
  public ExecutorService createSingleThreadExecutor() {
    return Executors.newSingleThreadExecutor();
  }
}

