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

import java.nio.file.Path;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The container images the integration tests run the applications in, built from the Dockerfiles under {@code src/test/docker}, with the applications built
 * by Maven. The paths come from system properties set by the build.
 */
final class FixtureImage {

  private FixtureImage() {
  }

  /**
   * Builds an image with the fixture application, in {@code classes} and {@code lib}, on the JDK the build runs the tests for.
   *
   * @param name the name of the directory of the Dockerfile, under {@code src/test/docker}
   * @return the image
   */
  static ImageFromDockerfile of(String name) {
    // the image is kept, so that the next build reuses its layers, and only adds the applications again
    return new ImageFromDockerfile("jsystemd-integration-tests-" + name + "-jdk" + jdk(), false)
        .withBuildArg("JDK", String.valueOf(jdk()))
        .withFileFromPath("Dockerfile", path("fixture.docker").resolve(name).resolve("Dockerfile"))
        .withFileFromPath("classes", path("fixture.classes"))
        .withFileFromPath("lib", path("fixture.lib"));
  }

  /**
   * Returns the major version of the JDK the applications run on in the containers, set by the build, which runs the tests once for each.
   *
   * @return the version, such as 21
   */
  static int jdk() {
    return Integer.parseInt(property("fixture.jdk"));
  }

  /**
   * Returns how the applications are expected to notify systemd on the JDK they run on, as logged by {@link Systemd#logStatus()}: with the Foreign Function
   * and Memory API from Java 22 on, as all the applications use a Unix domain socket, and through libsystemd before.
   *
   * @return the end of the line logged under systemd
   */
  static String expectedChannel() {
    return "notifications sent through: " + (jdk() >= 22 ? "Foreign Function and Memory API" : "libsystemd (JNA)");
  }

  /**
   * Returns the path in a system property set by the build.
   *
   * @param property the name of the system property
   * @return the path
   */
  static Path path(String property) {
    return Path.of(property(property));
  }

  private static String property(String property) {
    String value = System.getProperty(property);
    if (value == null) {
      throw new IllegalStateException("System property " + property + " is not set, run the tests with Maven");
    }
    return value;
  }
}
