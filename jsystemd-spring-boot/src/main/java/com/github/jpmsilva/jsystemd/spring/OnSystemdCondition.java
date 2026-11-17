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

package com.github.jpmsilva.jsystemd.spring;

import com.github.jpmsilva.jsystemd.Systemd;
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
 * @see Systemd#isUnderSystemd()
 * @see ConditionalOnSystemd
 */
class OnSystemdCondition extends SpringBootCondition {

  private final Builder message = ConditionMessage.forCondition(ConditionalOnSystemd.class);

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String environment = "Operating system is " + System.getProperty("os.name") + " and NOTIFY_SOCKET points to \"" + System.getenv("NOTIFY_SOCKET") + "\"";
    if (Systemd.isUnderSystemd()) {
      return ConditionOutcome.match(message.foundExactly(environment));
    }
    return ConditionOutcome.noMatch(message.notAvailable(environment));
  }
}
