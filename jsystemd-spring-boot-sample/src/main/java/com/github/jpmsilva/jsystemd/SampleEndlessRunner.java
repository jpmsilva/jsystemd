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

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Runs until the application stops when {@code sample.endless-runner} is {@code true}, for the integration tests to follow an application whose
 * command line runner never ends, so that it never accepts traffic.
 */
@Component
@ConditionalOnBooleanProperty("sample.endless-runner")
public class SampleEndlessRunner implements CommandLineRunner {

  @Override
  public void run(String... args) throws InterruptedException {
    // the application still stops on SIGTERM: the JVM exits once its shutdown hooks, such as the one closing the application context, have run, without
    // waiting for this thread
    Thread.sleep(Long.MAX_VALUE);
  }
}
