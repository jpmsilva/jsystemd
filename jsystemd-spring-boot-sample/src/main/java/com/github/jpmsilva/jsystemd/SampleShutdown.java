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

import java.time.Duration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Makes the shutdown of the sample take as long as {@code sample.shutdown} (none by default), while its beans are destroyed, for the integration tests to
 * follow a slow shutdown.
 */
@Component
public class SampleShutdown implements DisposableBean {

  private final Duration shutdown;

  public SampleShutdown(@Value("${sample.shutdown:0s}") Duration shutdown) {
    this.shutdown = shutdown;
  }

  @Override
  public void destroy() throws InterruptedException {
    Thread.sleep(shutdown.toMillis());
  }
}
