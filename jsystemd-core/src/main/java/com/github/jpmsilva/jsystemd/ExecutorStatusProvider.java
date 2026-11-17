/*
 * Copyright 2026 Joao Silva
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jpmsilva.jsystemd;

import static java.util.Objects.requireNonNull;

import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides information regarding a thread pool: the busy threads out of the threads in the pool, and
 * the tasks waiting for a thread, such as {@code workers: 3/10 threads, 5 queued}. For a {@link ScheduledThreadPoolExecutor}, the tasks waiting are those
 * scheduled to run, including the periodic ones between their runs, such as {@code scheduler: 1/2 threads, 4 scheduled}.
 *
 * <p>The thread pool can be supplied when the status is read, for thread pools that are created after this provider, such as those of Spring's task
 * executors: nothing is reported while the supplier returns <code>null</code>.
 *
 * @author Joao Silva
 */
public class ExecutorStatusProvider implements StatusProvider {

  private final String name;

  private final Supplier<? extends @Nullable ThreadPoolExecutor> executor;

  /**
   * Create a new ExecutorStatusProvider for the given thread pool.
   *
   * @param name the name of the thread pool, shown in the status
   * @param executor the thread pool
   */
  public ExecutorStatusProvider(String name, ThreadPoolExecutor executor) {
    this(name, supplier(executor));
  }

  /**
   * Create a new ExecutorStatusProvider for the thread pool given by the supplier, when the status is read.
   *
   * @param name the name of the thread pool, shown in the status
   * @param executor the supplier of the thread pool, which returns <code>null</code> while there is none
   */
  public ExecutorStatusProvider(String name, Supplier<? extends @Nullable ThreadPoolExecutor> executor) {
    this.name = requireNonNull(name, "Name must not be null");
    this.executor = requireNonNull(executor, "Executor must not be null");
  }

  private static Supplier<ThreadPoolExecutor> supplier(ThreadPoolExecutor executor) {
    requireNonNull(executor, "Executor must not be null");
    return () -> executor;
  }

  @Override
  public String status() {
    ThreadPoolExecutor pool = executor.get();
    if (pool == null) {
      return "";
    }
    return String.format(Locale.ROOT, "%s: %d/%d threads, %d %s", name, pool.getActiveCount(), pool.getPoolSize(), pool.getQueue().size(),
        pool instanceof ScheduledThreadPoolExecutor ? "scheduled" : "queued");
  }
}
