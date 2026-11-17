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

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Makes the startup of the sample fail when {@code sample.fail-startup} is {@code true}, for the integration tests to follow a failed startup.
 */
@Component
@ConditionalOnBooleanProperty("sample.fail-startup")
public class SampleStartupFailure {

  public SampleStartupFailure() {
    throw new IllegalStateException("The startup fails on purpose, as sample.fail-startup is true");
  }
}
