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

package com.github.jpmsilva.jsystemd.spring;

import static com.github.jpmsilva.jsystemd.spring.BeanFactoryUtilities.createdBeans;
import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.ExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the thread pools of the task executors and schedulers of the
 * application, such as the ones Spring Boot sets up for {@code @Async} and {@code @Scheduled} methods, each reported by an
 * {@link ExecutorStatusProvider} named after its bean, such as {@code applicationTaskExecutor: 2/8 threads, 5 queued, taskScheduler: 1/1 threads, 3 scheduled}.
 *
 * <p>The task executors are the {@link ThreadPoolTaskExecutor} and {@link ThreadPoolTaskScheduler} beans, found once all singleton beans are created, so
 * that lazy ones are not created. Those that run tasks on virtual threads, as Spring Boot sets them up with
 * {@code spring.threads.virtual.enabled=true}, have no thread pool, and are left out.
 *
 * @author Joao Silva
 */
public class TaskExecutorStatusProvider implements StatusProvider, SmartInitializingSingleton {

  private final ConfigurableListableBeanFactory beanFactory;

  // set once all singleton beans are created, read by the status updates
  private volatile List<ExecutorStatusProvider> executors = List.of();

  /**
   * Creates a new instance that finds the task executors in the given bean factory.
   *
   * @param beanFactory the bean factory to find the task executors in
   */
  public TaskExecutorStatusProvider(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = requireNonNull(beanFactory, "Bean factory must not be null");
  }

  /**
   * Finds the task executors among the singleton beans that are already created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    // sorted by name, so that the order does not depend on the order in which the beans are defined
    Map<String, ExecutorStatusProvider> found = new TreeMap<>();
    createdBeans(beanFactory, ThreadPoolTaskExecutor.class).forEach((name, executor) ->
        found.put(name, new ExecutorStatusProvider(name, initialized(executor::getThreadPoolExecutor))));
    createdBeans(beanFactory, ThreadPoolTaskScheduler.class).forEach((name, scheduler) ->
        found.put(name, new ExecutorStatusProvider(name, initialized(scheduler::getScheduledThreadPoolExecutor))));
    executors = List.copyOf(found.values());
  }

  /**
   * Returns the thread pool given by the supplier, or <code>null</code> while the task executor is not initialized.
   */
  private static Supplier<@Nullable ThreadPoolExecutor> initialized(Supplier<? extends ThreadPoolExecutor> executor) {
    return () -> {
      try {
        return executor.get();
      } catch (IllegalStateException e) {
        return null;
      }
    };
  }

  @Override
  public String status() {
    List<String> statuses = new ArrayList<>();
    for (ExecutorStatusProvider executor : executors) {
      String status = executor.status();
      if (!status.isEmpty()) {
        statuses.add(status);
      }
    }
    return String.join(", ", statuses);
  }
}
