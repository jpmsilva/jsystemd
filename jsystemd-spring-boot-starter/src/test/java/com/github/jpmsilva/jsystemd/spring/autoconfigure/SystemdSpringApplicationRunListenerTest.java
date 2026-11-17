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

package com.github.jpmsilva.jsystemd.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;

import com.github.jpmsilva.jsystemd.BufferPoolStatusProvider;
import com.github.jpmsilva.jsystemd.CgroupMemoryStatusProvider;
import com.github.jpmsilva.jsystemd.ClassLoaderStatusProvider;
import com.github.jpmsilva.jsystemd.FileDescriptorStatusProvider;
import com.github.jpmsilva.jsystemd.HeapStatusProvider;
import com.github.jpmsilva.jsystemd.NonHeapStatusProvider;
import com.github.jpmsilva.jsystemd.OldGenerationStatusProvider;
import com.github.jpmsilva.jsystemd.PhaseStatusProvider;
import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.SystemdNotify;
import com.github.jpmsilva.jsystemd.SystemdProcess;
import com.github.jpmsilva.jsystemd.ThreadStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ActuatorHealthStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationContextStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider;
import com.github.jpmsilva.jsystemd.spring.AvailabilityStatusProvider;
import com.github.jpmsilva.jsystemd.spring.HikariStatusProvider;
import com.github.jpmsilva.jsystemd.spring.JettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.LiquibaseStatusProvider;
import com.github.jpmsilva.jsystemd.spring.NettyStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ShutdownStatusProvider;
import com.github.jpmsilva.jsystemd.spring.SystemdLifecycle;
import com.github.jpmsilva.jsystemd.spring.TaskExecutorStatusProvider;
import com.github.jpmsilva.jsystemd.spring.TomcatStatusProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;

/**
 * Tests for {@link SystemdSpringApplicationRunListener}.
 *
 * @author Joao Silva
 */
class SystemdSpringApplicationRunListenerTest {

  private static final String CONTEXT_PROVIDER_BEAN = "systemdApplicationContextStatusProvider";
  private static final String CLOSER_BEAN = "systemdCloser";
  private static final long WATCHDOG_USEC = 10_000_000L;

  private final DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext();

  private MockedStatic<?> utilities;
  private MockedStatic<SystemdNotify> notify;
  private SystemdSpringApplicationRunListener listener;
  private Systemd systemd;

  @BeforeEach
  void setUp() {
    utilities = SystemdProcess.mockEnvironment();
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(true);
    // each test acts as if it were a process of its own, with an instance of its own
    SystemdProcess.reset();
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(0L);
    notify = Mockito.mockStatic(SystemdNotify.class);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (systemd != null) {
      systemd.close();
    }
    notify.close();
    utilities.close();
  }

  private GenericApplicationContext run(String... properties) {
    return run(new GenericApplicationContext(), properties);
  }

  private GenericApplicationContext run(GenericApplicationContext context, String... properties) {
    StandardEnvironment environment = new StandardEnvironment();
    TestPropertyValues.of(properties).applyTo(environment);
    return run(context, environment);
  }

  private GenericApplicationContext run(GenericApplicationContext context, StandardEnvironment environment) {
    listener = new SystemdSpringApplicationRunListener(new SpringApplication(), new String[0]);

    listener.starting(bootstrapContext);
    listener.environmentPrepared(bootstrapContext, environment);
    listener.contextPrepared(context);

    systemd = Systemd.instance();
    return context;
  }

  private List<Class<?>> providers() {
    return systemd.getStatusProviders().stream().<Class<?>>map(Object::getClass).toList();
  }

  @Test
  void setsUpTheCloserAndAllProvidersByDefault() {
    GenericApplicationContext context = run();

    assertThat(listener.enabled()).isTrue();
    assertThat(context.getBeanFactory().containsBean(CLOSER_BEAN)).isTrue();
    assertThat(context.getBeanFactory().getSingleton(CONTEXT_PROVIDER_BEAN)).isInstanceOf(ApplicationContextStatusProvider.class);
    assertThat(providers()).containsExactly(ApplicationRunStatusProvider.class, ApplicationContextStatusProvider.class);
  }

  @Test
  void sendsTheStatusEveryFiveSeconds() {
    run();

    assertThat(SystemdProcess.statusUpdatePeriod(systemd)).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void sendsTheStatusAtTheConfiguredPeriod() {
    run("systemd.status-update-period=30s");

    assertThat(SystemdProcess.statusUpdatePeriod(systemd)).isEqualTo(Duration.ofSeconds(30));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-5s", "999ns"})
  void rejectsStatusPeriodsShorterThanAMicrosecond(String period) {
    assertThatThrownBy(() -> run("systemd.status-update-period=" + period)).isInstanceOf(InvalidConfigurationPropertyValueException.class)
        .hasMessageContaining("systemd.status-update-period");
  }

  @Test
  void readyOnceAcceptingTrafficByDefault() {
    GenericApplicationContext context = run();

    // the autoconfiguration tells systemd once the application accepts traffic, after the runners have run
    listener.started(context, Duration.ofSeconds(1));

    notify.verify(SystemdProcess.ready(), never());
    assertThat(systemd.isReady()).isFalse();
  }

  @Test
  void readyOnceStartedWhenConfigured() {
    GenericApplicationContext context = run("systemd.ready-event=application-started");

    listener.started(context, Duration.ofSeconds(1));

    notify.verify(SystemdProcess.ready());
    assertThat(systemd.isReady()).isTrue();
  }

  @Test
  void rejectsUnknownReadyEvents() {
    assertThatThrownBy(() -> run("systemd.ready-event=application-ready")).isInstanceOf(BindException.class)
        .hasMessageContaining("systemd.ready-event");
  }

  @Test
  void reportsStartupStatesAndExtendsTimeoutWhenApplicationRunProviderIsEnabled() {
    run();

    notify.verify(SystemdProcess.status(() -> contains("State: starting")));
    notify.verify(SystemdProcess.status(() -> contains("State: environment prepared")));
    notify.verify(SystemdProcess.status(() -> contains("State: context prepared")));
    notify.verify(SystemdProcess.extendTimeout(() -> anyLong()), Mockito.times(3));
  }

  @Test
  void nothingIsSentToSystemdWhenApplicationRunProviderIsDisabled() {
    run("systemd.status-providers.application-run.enabled=false");

    notify.verify(SystemdProcess.status(() -> anyString()), never());
    notify.verify(SystemdProcess.extendTimeout(() -> anyLong()), never());
  }

  @Test
  void doesNothingWhenNotUnderSystemd() {
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(false);

    GenericApplicationContext context = run();

    assertThat(listener.enabled()).isFalse();
    assertThat(context.getBeanFactory().containsBean(CLOSER_BEAN)).isFalse();
    assertThat(context.getBeanFactory().containsSingleton(CONTEXT_PROVIDER_BEAN)).isFalse();
  }

  // the property sources Spring Cloud adds to the environment of the bootstrap application, and of the applications run to refresh the configuration
  @ParameterizedTest
  @ValueSource(strings = {"bootstrap", "refreshArgs"})
  void doesNothingInSpringCloudAuxiliaryApplications(String propertySource) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource(propertySource, Map.of()));

    run(new GenericApplicationContext(), environment);

    assertThat(listener.enabled()).isFalse();
    notify.verifyNoInteractions();
    // not even checking for systemd, so that, when not under systemd, the warning is logged once, by the actual application, rather than on every refresh
    utilities.verify(SystemdProcess.isUnderSystemd(), never());
  }

  @Test
  void runsAfterSpringBootInitializesLogging() {
    // loads the run listeners the same way SpringApplication does
    ArgumentResolver arguments = ArgumentResolver.of(SpringApplication.class, new SpringApplication()).and(String[].class, new String[0]);
    List<String> listeners = SpringFactoriesLoader.forDefaultResourceLocation().load(SpringApplicationRunListener.class, arguments).stream()
        .map(it -> it.getClass().getSimpleName())
        .toList();

    assertThat(listeners).containsSubsequence("EventPublishingRunListener", "SystemdSpringApplicationRunListener");
  }

  // what is logged is tested with Systemd: the listener is only responsible for logging it, once logging is initialized
  @Test
  void logsTheStatusOfTheIntegrationWhenUnderSystemd() {
    Systemd mock = Mockito.mock(Systemd.class);
    SystemdProcess.replace(mock);

    run();

    Mockito.verify(mock).logStatus();
  }

  @Test
  void logsTheStatusOfTheIntegrationWhenNotUnderSystemd() {
    // such as when the notifications to systemd could not be set up, which the status then warns about
    utilities.when(SystemdProcess.isUnderSystemd()).thenReturn(false);
    Systemd mock = Mockito.mock(Systemd.class);
    SystemdProcess.replace(mock);

    run();

    Mockito.verify(mock).logStatus();
  }

  @Test
  void applicationContextProviderCanBeDisabled() {
    GenericApplicationContext context = run("systemd.status-providers.application-context.enabled=false");

    assertThat(context.getBeanFactory().containsSingleton(CONTEXT_PROVIDER_BEAN)).isFalse();
    assertThat(providers()).containsExactly(ApplicationRunStatusProvider.class);
  }

  @Test
  void applicationRunProviderCanBeDisabled() {
    GenericApplicationContext context = run("systemd.status-providers.application-run.enabled=false");

    assertThat(providers()).containsExactly(ApplicationContextStatusProvider.class);
    assertThat(context.getBeanFactory().containsSingleton(CONTEXT_PROVIDER_BEAN)).isTrue();
  }

  @Test
  void keepsStartupStatusProvidersFirstOnceAutoconfigurationAddsTheStatusProviderBeans() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    run(context);
    context.register(SystemdAutoConfiguration.class);

    context.refresh();

    // the startup status providers, registered by the run listener, then the status provider beans in order, each once
    assertThat(providers()).containsExactly(ApplicationRunStatusProvider.class, ApplicationContextStatusProvider.class, LiquibaseStatusProvider.class,
        ShutdownStatusProvider.class, PhaseStatusProvider.class, ActuatorHealthStatusProvider.class, AvailabilityStatusProvider.class, HeapStatusProvider.class,
        OldGenerationStatusProvider.class, CgroupMemoryStatusProvider.class, HikariStatusProvider.class, ThreadStatusProvider.class,
        FileDescriptorStatusProvider.class, TomcatStatusProvider.class, JettyStatusProvider.class, NettyStatusProvider.class, TaskExecutorStatusProvider.class,
        NonHeapStatusProvider.class, BufferPoolStatusProvider.class, ClassLoaderStatusProvider.class);
    context.close();
  }

  @Test
  void closerIsStillRegisteredWhenAllProvidersAreDisabled() {
    GenericApplicationContext context = run("systemd.status-providers.application-run.enabled=false",
        "systemd.status-providers.application-context.enabled=false");

    assertThat(context.getBeanFactory().containsBean(CLOSER_BEAN)).isTrue();
    assertThat(providers()).isEmpty();
  }

  @Test
  void closesSystemdAfterAllOtherBeans() {
    Map<String, Boolean> closedWhenDestroyed = new ConcurrentHashMap<>();
    GenericApplicationContext context = new GenericApplicationContext();
    // registered before the listener registers the closer, as an application context initializer could do
    context.registerBean("early", DisposableBean.class, () -> () -> closedWhenDestroyed.put("early", SystemdProcess.isClosed(systemd)));
    run(context);
    context.registerBean("late", DisposableBean.class, () -> () -> closedWhenDestroyed.put("late", SystemdProcess.isClosed(systemd)));
    context.refresh();

    context.close();

    assertThat(closedWhenDestroyed).containsOnly(entry("early", false), entry("late", false));
    assertThat(SystemdProcess.isClosed(systemd)).isTrue();
  }

  @Test
  void closesSystemdAfterStoppingIsNotified() {
    GenericApplicationContext context = run();
    context.registerBean(SystemdLifecycle.class, () -> new SystemdLifecycle(context));
    context.refresh();
    AtomicReference<Boolean> closedWhenStopping = new AtomicReference<>();
    notify.when(SystemdProcess.stopping()).thenAnswer(invocation -> {
      closedWhenStopping.set(SystemdProcess.isClosed(systemd));
      return null;
    });

    context.close();

    // the watchdog and status updates must keep running until systemd is told that the application is stopping
    assertThat(closedWhenStopping).hasValue(false);
    assertThat(SystemdProcess.isClosed(systemd)).isTrue();
  }

  @Test
  void closesSystemdWhenStartupFailsBeforeContextIsRefreshed() {
    GenericApplicationContext context = run();

    // the context was never refreshed, so Spring does not close it, nor the beans it created
    listener.failed(context, new IllegalStateException("failure"));

    assertThat(SystemdProcess.isClosed(systemd)).isTrue();
  }

  @Test
  void buildsSystemdWithWatchdogWhenEnabledBySystemd() {
    utilities.when(SystemdProcess.watchdogUsec()).thenReturn(WATCHDOG_USEC);

    run();

    assertThat(SystemdProcess.watchdogFuture(systemd)).isNotNull();
  }

}
