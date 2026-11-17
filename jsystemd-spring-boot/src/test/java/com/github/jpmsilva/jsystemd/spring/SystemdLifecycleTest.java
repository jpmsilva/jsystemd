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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Tests for {@link SystemdLifecycle}, with real application contexts.
 *
 * @author Joao Silva
 */
class SystemdLifecycleTest {

  private final Systemd systemd = mock(Systemd.class);

  @BeforeEach
  void setUp() {
    SystemdProcess.replace(systemd);
  }

  @AfterEach
  void tearDown() {
    SystemdProcess.reset();
  }

  private static GenericApplicationContext context() {
    GenericApplicationContext context = new GenericApplicationContext();
    context.registerBean(SystemdLifecycle.class, () -> new SystemdLifecycle(context));
    context.refresh();
    return context;
  }

  @Test
  void sendsStoppingWhenTheContextCloses() {
    GenericApplicationContext context = context();

    context.close();

    verify(systemd).stopping();
  }

  @Test
  void doesNotSendStoppingWhenTheContextIsOnlyStopped() {
    try (GenericApplicationContext context = context()) {
      context.stop();
      context.start();

      verify(systemd, never()).stopping();
    }
  }

  @Test
  void doesNotSendStoppingWhenTheContextIsPaused() {
    try (GenericApplicationContext context = context()) {
      // as the pause endpoint of Spring Cloud, or a CRaC checkpoint, which stop the life cycle beans
      context.pause();
      context.restart();

      verify(systemd, never()).stopping();
    }
  }

  @Test
  void ignoresTheClosingOfParentContexts() {
    // as the bootstrap context of Spring Cloud, the parent of the application context
    GenericApplicationContext parent = new GenericApplicationContext();
    parent.refresh();
    GenericApplicationContext context = new GenericApplicationContext(parent);
    context.registerBean(SystemdLifecycle.class, () -> new SystemdLifecycle(context));
    context.refresh();

    parent.close();

    verify(systemd, never()).stopping();
    context.close();
    verify(systemd).stopping();
  }

  @Test
  void rejectsANullContext() {
    assertThatThrownBy(() -> new SystemdLifecycle(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void ignoresTheClosingOfChildContexts() {
    try (GenericApplicationContext context = context()) {
      // as the context of the management endpoints when they use a port of their own
      AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
      child.setParent(context);
      child.refresh();

      child.close();

      verify(systemd, never()).stopping();
    }
  }
}
