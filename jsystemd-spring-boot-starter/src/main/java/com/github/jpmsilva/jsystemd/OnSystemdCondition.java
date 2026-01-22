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

import static com.github.jpmsilva.jsystemd.SystemdUtilities.isUnderSystemd;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.notifySocketPath;
import static com.github.jpmsilva.jsystemd.SystemdUtilities.osName;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionMessage.Builder;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link Condition} that checks if the program is running under systemd.
 *
 * @author Joao Silva
 * @see SystemdUtilities#isUnderSystemd()
 * @see ConditionalOnSystemd
 */
class OnSystemdCondition extends SpringBootCondition {

  @NonNull
  private final Builder message = ConditionMessage.forCondition(ConditionalOnSystemd.class);

  @Override
  @NullMarked
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    if (isUnderSystemd()) {
      return ConditionOutcome.match(message.foundExactly(
          "Operating system is " + osName() + " and NOTIFY_SOCKET points to \"" + notifySocketPath() + "\""));
    }
    return ConditionOutcome.noMatch(message.notAvailable(
        "Operating system is " + osName() + " and NOTIFY_SOCKET points to \"" + notifySocketPath() + "\""));
  }
}
