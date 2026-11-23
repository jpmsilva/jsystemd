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

import com.github.jpmsilva.jsystemd.RunningStatusProvider;
import com.github.jpmsilva.jsystemd.StatusProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskExecutionOutcome;

/**
 * Implementation of {@link StatusProvider} that provides information regarding the scheduled tasks of the application, such as its {@code @Scheduled}
 * methods: how many there are, how many are running, and how many failed the last time they ran, such as
 * {@code Scheduled tasks: 5 (1 running; 1 failing)}. Nothing is reported while there are no scheduled tasks.
 *
 * <p>A scheduled task that fails only logs its failure, and runs again when it is next due, so that a task that fails every time is easily missed: it is
 * reported as failing from its failure until it next runs, and again if that run fails too. A task that is running is reported as such, whatever the
 * outcome of its previous run.
 *
 * <p>The tasks are those of the {@link ScheduledTaskHolder} beans, such as the one Spring sets up for the {@code @Scheduled} methods, found once all
 * singleton beans are created, so that lazy ones are not created; the tasks themselves are read on every status update, so that those scheduled later are
 * reported as well. Each task is counted from the outcome of its last run, which Spring Framework records.
 *
 * <p>The tasks only run while the application does, so they are only reported then, as a {@link RunningStatusProvider}: once the application stops, its
 * tasks are cancelled, and their last outcome no longer tells anything.
 *
 * @author Joao Silva
 */
public class ScheduledTaskStatusProvider implements RunningStatusProvider, SmartInitializingSingleton {

  private final ConfigurableListableBeanFactory beanFactory;

  // set once all singleton beans are created, read by the status updates
  private volatile List<ScheduledTaskHolder> holders = List.of();

  /**
   * Creates a new instance that finds the holders of the scheduled tasks in the given bean factory.
   *
   * @param beanFactory the bean factory to find the holders of the scheduled tasks in
   */
  public ScheduledTaskStatusProvider(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = requireNonNull(beanFactory, "Bean factory must not be null");
  }

  /**
   * Finds the holders of the scheduled tasks among the singleton beans that are already created.
   */
  @Override
  public void afterSingletonsInstantiated() {
    holders = List.copyOf(createdBeans(beanFactory, ScheduledTaskHolder.class).values());
  }

  @Override
  public String status() {
    // a task held by several holders, such as a registrar that is also a bean, is only counted once
    Set<ScheduledTask> tasks = Collections.newSetFromMap(new IdentityHashMap<>());
    for (ScheduledTaskHolder holder : holders) {
      tasks.addAll(holder.getScheduledTasks());
    }
    if (tasks.isEmpty()) {
      return "";
    }
    int running = 0;
    int failing = 0;
    for (ScheduledTask task : tasks) {
      TaskExecutionOutcome.Status outcome = task.getTask().getLastExecutionOutcome().status();
      if (outcome == TaskExecutionOutcome.Status.STARTED) {
        running++;
      } else if (outcome == TaskExecutionOutcome.Status.ERROR) {
        failing++;
      }
    }
    // the statuses of all providers are joined with commas, so the details are separated otherwise
    List<String> details = new ArrayList<>();
    if (running > 0) {
      details.add(String.format(Locale.ROOT, "%d running", running));
    }
    if (failing > 0) {
      details.add(String.format(Locale.ROOT, "%d failing", failing));
    }
    return details.isEmpty() ? String.format(Locale.ROOT, "Scheduled tasks: %d", tasks.size())
        : String.format(Locale.ROOT, "Scheduled tasks: %d (%s)", tasks.size(), String.join("; ", details));
  }
}
