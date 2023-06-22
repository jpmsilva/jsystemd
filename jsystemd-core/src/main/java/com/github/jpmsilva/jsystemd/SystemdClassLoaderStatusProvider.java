/*
 * Copyright 2018-2023 Joao Silva
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

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;

/**
 * Implementation of {@link SystemdStatusProvider} that provides information regarding loaded classes.
 *
 * @author Joao Silva
 */
@Order(-1000)
public class SystemdClassLoaderStatusProvider implements SystemdStatusProvider {

  /**
   * Create a new SystemdNotifyClassLoaderStatusProvider.
   */
  public SystemdClassLoaderStatusProvider() {
  }

  @Override
  public @NotNull String status() {
    return Optional.ofNullable(ManagementFactory.getClassLoadingMXBean())
        .map(ClassLoadingMXBean::getLoadedClassCount)
        .map(t -> String.format("Classes: %d", t))
        .orElse("");
  }
}
