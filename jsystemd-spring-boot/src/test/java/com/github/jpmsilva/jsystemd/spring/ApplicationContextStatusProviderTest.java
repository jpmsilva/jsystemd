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

import static com.github.jpmsilva.jsystemd.spring.ApplicationContextStatusProvider.NOTIFY_INTERVAL_NANOS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.ChildBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Tests for {@link ApplicationContextStatusProvider}.
 *
 * @author Joao Silva
 */
class ApplicationContextStatusProviderTest {

  private final AtomicLong nanoTime = new AtomicLong();
  private final AtomicLong containsSingletonCalls = new AtomicLong();
  // counts the lookups, so that the tests can tell how often the singletons created so far are counted
  private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory() {
    @Override
    public boolean containsSingleton(String beanName) {
      containsSingletonCalls.incrementAndGet();
      return super.containsSingleton(beanName);
    }
  };

  private MockedStatic<SystemdNotify> notify;
  private Systemd systemd;

  @BeforeEach
  void setUp() {
    notify = Mockito.mockStatic(SystemdNotify.class);
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
    systemd = Systemd.instance();
    for (String name : new String[]{"first", "second", "third"}) {
      factory.registerBeanDefinition(name, new RootBeanDefinition(Object.class));
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    systemd.close();
    notify.close();
  }

  private ApplicationContextStatusProvider provider() {
    ApplicationContextStatusProvider provider = new ApplicationContextStatusProvider(factory, nanoTime::get);
    systemd.addStatusProviders(provider);
    return provider;
  }

  // reports the creation of a bean the way the bean factory does, once the beans created before it are in the factory
  private void create(ApplicationContextStatusProvider provider, String name) {
    provider.postProcessBeforeInitialization(new Object(), name);
    factory.registerSingleton(name, new Object());
  }

  @Test
  void notifiesTheFirstBeanRightAway() {
    ApplicationContextStatusProvider provider = provider();

    create(provider, "first");

    notify.verify(SystemdProcess.status(() -> "Creating bean 0 of 3"));
    notify.verify(SystemdProcess.extendTimeout(() -> anyLong()));
  }

  @Test
  void doesNotNotifyAgainWithinTheInterval() {
    ApplicationContextStatusProvider provider = provider();
    create(provider, "first");
    nanoTime.addAndGet(NOTIFY_INTERVAL_NANOS - 1);

    create(provider, "second");

    notify.verify(SystemdProcess.status(() -> anyString()), times(1));
    notify.verify(SystemdProcess.extendTimeout(() -> anyLong()), times(1));
    // the status is current whenever it is read, such as by the periodic status update
    assertThat(provider.status()).isEqualTo("Creating bean 2 of 3");
  }

  @Test
  void countsTheCreatedBeansOnlyWhenTheStatusIsRead() {
    ApplicationContextStatusProvider provider = provider();
    // registering the bean definitions looks them up too, so only the lookups made while the beans are created are counted
    containsSingletonCalls.set(0);
    provider.postProcessBeforeInitialization(new Object(), "first");
    provider.postProcessBeforeInitialization(new Object(), "second");
    provider.postProcessBeforeInitialization(new Object(), "third");

    // only the first bean notifies systemd, reading the status: a single pass over the three singletons, rather than one for every bean created
    assertThat(containsSingletonCalls).hasValue(3);
  }

  @Test
  void leavesOutLazyAndAbstractBeans() {
    RootBeanDefinition lazy = new RootBeanDefinition(Object.class);
    lazy.setLazyInit(true);
    factory.registerBeanDefinition("lazy", lazy);
    RootBeanDefinition template = new RootBeanDefinition(Object.class);
    template.setAbstract(true);
    factory.registerBeanDefinition("template", template);
    ApplicationContextStatusProvider provider = provider();

    create(provider, "first");

    // the three beans created on startup, as the lazy one may never be created, and the abstract one never is
    assertThat(provider.status()).isEqualTo("Creating bean 1 of 3");
  }

  @Test
  void leavesOutBeansInheritingLazinessFromTheirParent() {
    RootBeanDefinition parent = new RootBeanDefinition(Object.class);
    parent.setAbstract(true);
    parent.setLazyInit(true);
    factory.registerBeanDefinition("parent", parent);
    factory.registerBeanDefinition("child", new ChildBeanDefinition("parent"));
    ApplicationContextStatusProvider provider = provider();

    create(provider, "first");

    assertThat(provider.status()).isEqualTo("Creating bean 1 of 3");
  }

  @Test
  void reportsNothingWhenAllBeansAreLazy() {
    // as with spring.main.lazy-initialization=true
    for (String name : factory.getBeanDefinitionNames()) {
      factory.getBeanDefinition(name).setLazyInit(true);
    }
    ApplicationContextStatusProvider provider = provider();

    provider.postProcessBeforeInitialization(new Object(), "first");

    assertThat(provider.status()).isEmpty();
  }

  @Test
  void reportsNothingBeforeTheFirstBeanIsCreated() {
    assertThat(provider().status()).isEmpty();
  }

  @Test
  void doesNotNotifyForBeansCreatedOnceReady() {
    ApplicationContextStatusProvider provider = provider();
    systemd.ready();
    notify.clearInvocations();

    // such as a prototype or request scoped bean
    create(provider, "first");

    notify.verifyNoInteractions();
  }

  @Test
  void doesNotExtendTheShutdownTimeoutForBeansCreatedWhileStopping() {
    ApplicationContextStatusProvider provider = provider();
    systemd.ready();
    systemd.stopping();
    notify.clearInvocations();

    create(provider, "first");

    notify.verifyNoInteractions();
  }

  @Test
  void reportsNothingOnceReady() {
    ApplicationContextStatusProvider provider = provider();
    create(provider, "first");

    systemd.ready();

    // left out of the status once ready, as it only reports while the application starts up
    notify.verify(SystemdProcess.status(() -> "Creating bean 0 of 3"));
    notify.verify(SystemdProcess.status(() -> ""));
  }

  @Test
  void notifiesAgainOnceTheIntervalHasPassed() {
    ApplicationContextStatusProvider provider = provider();
    create(provider, "first");
    nanoTime.addAndGet(NOTIFY_INTERVAL_NANOS / 2);
    create(provider, "second");
    nanoTime.addAndGet(NOTIFY_INTERVAL_NANOS / 2);

    create(provider, "third");

    notify.verify(SystemdProcess.status(() -> anyString()), times(2));
    notify.verify(SystemdProcess.status(() -> "Creating bean 2 of 3"));
    notify.verify(SystemdProcess.extendTimeout(() -> anyLong()), times(2));
  }

  @Test
  void reportsTheProgressRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "٣" for 3
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      ApplicationContextStatusProvider provider = provider();
      create(provider, "first");

      assertThat(provider.status()).isEqualTo("Creating bean 1 of 3");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void notifiesTheFirstBeanRightAwayWhateverTheValueOfTheClock() {
    // System.nanoTime() may be any value, even negative, so the interval must be measured without overflowing
    nanoTime.set(Long.MIN_VALUE);
    ApplicationContextStatusProvider provider = provider();

    create(provider, "first");

    notify.verify(SystemdProcess.status(() -> "Creating bean 0 of 3"));
  }
}
