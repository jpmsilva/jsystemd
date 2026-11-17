/*
 * Copyright 2018-2026 Joao Silva
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

import com.github.jpmsilva.jsystemd.Systemd;
import com.github.jpmsilva.jsystemd.spring.ApplicationContextStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider;
import com.github.jpmsilva.jsystemd.spring.ApplicationRunStatusProvider.ApplicationState;
import com.github.jpmsilva.jsystemd.spring.autoconfigure.SystemdProperties.ReadyEvent;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * A Spring Application Run Listener that sets up the integration with systemd, as early as possible during the startup of the application.
 *
 * <p>Once the environment is prepared, it configures the {@link Systemd} instance and logs the integration status, and sets up an
 * {@link ApplicationRunStatusProvider} to provide status updates of the current phase of the application life cycle. Once the application context is
 * prepared, it registers a bean that closes the instance when the context is closed, and sets up an {@link ApplicationContextStatusProvider} to report the
 * bean creation progress. Once the application has started, it tells systemd that the application is ready, if so configured with
 * {@code systemd.ready-event=application-started}, rather than once it accepts traffic. The instance is closed if the application fails to start.
 *
 * <p>Applications that configure the instance themselves depend on {@code jsystemd-spring-boot} rather than the starter.
 *
 * <p>Nothing is set up in the applications that Spring Cloud runs next to the actual application (see the "Application hierarchies" page of the
 * documentation).
 *
 * @author Joao Silva
 * @see SpringApplicationRunListener
 */
public class SystemdSpringApplicationRunListener implements SpringApplicationRunListener, Ordered {

  /**
   * The order of this listener. It must run after Spring Boot's {@code EventPublishingRunListener} (order 0), which initializes logging when the environment is
   * prepared, so that the systemd integration status can be logged. It is kept away from {@link Ordered#LOWEST_PRECEDENCE} so that other listeners can still be
   * ordered after it.
   */
  public static final int ORDER = LOWEST_PRECEDENCE - 100;

  private static final Logger logger = LoggerFactory.getLogger(SystemdSpringApplicationRunListener.class);
  private static final String CLOSER_BEAN_NAME = "systemdCloser";

  /**
   * The property sources that Spring Cloud adds to the environment of the applications it runs next to the actual application: {@code bootstrap} for the
   * bootstrap context (when the legacy bootstrap is enabled), and {@code refreshArgs} for the applications run on each refresh of the configuration. Matched
   * by name, so that Spring Cloud is not needed on the classpath.
   */
  private static final Set<String> SPRING_CLOUD_AUXILIARY_PROPERTY_SOURCES = Set.of("bootstrap", "refreshArgs");

  // whether the integration is set up, once the environment is prepared
  private boolean enabled;
  @Nullable
  private ApplicationRunStatusProvider provider;
  private boolean applicationContextEnabled;
  private ReadyEvent readyEvent = ReadyEvent.ACCEPTING_TRAFFIC;

  /**
   * Mandatory constructor of SpringApplicationRunListener.
   *
   * <p>Nothing is set up here, because which status providers are enabled can only be determined once the environment is prepared.
   *
   * @param springApplication the current Spring Application
   * @param args the arguments passed to the Spring Application
   */
  @SuppressWarnings({"PMD.UnusedFormalParameter", "unused"})
  public SystemdSpringApplicationRunListener(SpringApplication springApplication, String[] args) {
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
    if (isSpringCloudAuxiliary(environment)) {
      // only the actual application talks to systemd: an auxiliary application would send its own status, and watchdog heartbeats regardless of the health
      // of the actual application
      return;
    }
    if (!Systemd.isUnderSystemd()) {
      Systemd.instance().logStatus();
      return;
    }
    // bound here, before any bean exists, so validated here too, rather than as a bean
    final SystemdProperties properties = Binder.get(environment).bind(SystemdProperties.PREFIX, SystemdProperties.class)
        .orElseGet(SystemdProperties::new);
    properties.afterPropertiesSet();

    Systemd systemd = Systemd.instance();
    enabled = true;
    systemd.enableWatchdog();
    systemd.enableStatusUpdate(properties.getStatusUpdatePeriod());
    systemd.logStatus();
    applicationContextEnabled = properties.getStatusProviders().getApplicationContext().isEnabled();
    readyEvent = properties.getReadyEvent();
    if (properties.getStatusProviders().getApplicationRun().isEnabled()) {
      provider = new ApplicationRunStatusProvider();
      // registered before any other status provider, and before reporting the first states
      systemd.addStatusProviders(provider);
      provider.state(ApplicationState.STARTING);
      provider.state(ApplicationState.ENVIRONMENT_PREPARED);
    }
  }

  @Override
  public void contextPrepared(ConfigurableApplicationContext context) {
    if (!enabled) {
      return;
    }
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_PREPARED);
    }
    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    registerCloser(beanFactory);
    // created right away, before any other bean: beans are destroyed in the reverse order of their creation, so this one is closed last, keeping the
    // periodic tasks running for as long as possible while the context is closed
    beanFactory.getBean(CLOSER_BEAN_NAME);
    if (applicationContextEnabled) {
      ApplicationContextStatusProvider applicationContextStatusProvider = new ApplicationContextStatusProvider(beanFactory);
      // registered as a bean, as it is also a bean post processor, which follows the bean creation
      beanFactory.registerSingleton("systemdApplicationContextStatusProvider", applicationContextStatusProvider);
      Systemd.instance().addStatusProviders(applicationContextStatusProvider);
    }
  }

  private static boolean isSpringCloudAuxiliary(ConfigurableEnvironment environment) {
    return SPRING_CLOUD_AUXILIARY_PROPERTY_SOURCES.stream().anyMatch(environment.getPropertySources()::contains);
  }

  /**
   * Registers a bean that closes the instance, through a bean definition rather than as a singleton, as only beans with a definition receive destruction
   * callbacks: Spring then closes it, as it is {@link AutoCloseable}, when the context is closed, after the {@link org.springframework.context.SmartLifecycle}
   * beans are stopped.
   */
  private static void registerCloser(ConfigurableListableBeanFactory beanFactory) {
    if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
      throw new IllegalStateException("Bean factory " + beanFactory.getClass().getName() + " does not support registering bean definitions");
    }
    AutoCloseable closer = () -> Systemd.instance().close();
    registry.registerBeanDefinition(CLOSER_BEAN_NAME, BeanDefinitionBuilder.genericBeanDefinition(AutoCloseable.class, () -> closer).getBeanDefinition());
  }

  /**
   * Closes the instance if the application fails to start, as the context may not have created the bean yet, in which case it would not close it.
   */
  @Override
  public void failed(@Nullable ConfigurableApplicationContext context, Throwable exception) {
    if (enabled) {
      close();
    }
  }

  private static void close() {
    try {
      Systemd.instance().close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      logger.warn("Could not close the Systemd instance", e);
    }
  }

  // for tests: whether the integration is set up
  boolean enabled() {
    return enabled;
  }

  @Override
  public void contextLoaded(ConfigurableApplicationContext context) {
    if (provider != null) {
      provider.state(ApplicationState.CONTEXT_LOADED);
    }
  }

  @Override
  public void started(ConfigurableApplicationContext context, @Nullable Duration timeTaken) {
    if (provider != null) {
      provider.state(ApplicationState.STARTED, timeTaken);
    }
    // before the runners, which may run for long, or never end; otherwise, the autoconfiguration tells systemd once the application accepts traffic
    if (readyEvent == ReadyEvent.APPLICATION_STARTED) {
      Systemd.instance().ready();
    }
  }

  @Override
  public void ready(ConfigurableApplicationContext context, @Nullable Duration timeTaken) {
    if (provider != null) {
      provider.state(ApplicationState.READY, timeTaken);
    }
  }
}
