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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

/**
 * Tests for {@link BuildInfoStatusProvider}.
 *
 * @author Joao Silva
 */
class BuildInfoStatusProviderTest {

  // as Spring Boot reads them from META-INF/build-info.properties, without the "build." prefix
  private static BuildProperties build(String version) {
    Properties properties = new Properties();
    properties.setProperty("group", "com.example");
    properties.setProperty("artifact", "myservice");
    properties.setProperty("version", version);
    return new BuildProperties(properties);
  }

  // as Spring Boot reads them from git.properties, without the "git." prefix
  private static GitProperties git(String key, String commit) {
    Properties properties = new Properties();
    properties.setProperty("branch", "main");
    properties.setProperty(key, commit);
    return new GitProperties(properties);
  }

  @Test
  void reportsTheVersionOfTheBuildAndTheShortCommitId() {
    assertThat(new BuildInfoStatusProvider(build("1.4.2"), git("commit.id", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678")).status())
        .isEqualTo("Version: 1.4.2 (a1b2c3d)");
  }

  @Test
  void reportsTheAbbreviatedCommitIdWhenThereIsOne() {
    // as the Git commit id plugins generate it, with the length they are configured with
    assertThat(new BuildInfoStatusProvider(build("1.4.2"), git("commit.id.abbrev", "a1b2c3d4e5")).status()).isEqualTo("Version: 1.4.2 (a1b2c3d4e5)");
  }

  @Test
  void reportsOnlyTheVersionWithoutACommit() {
    assertThat(new BuildInfoStatusProvider(build("1.4.2"), null).status()).isEqualTo("Version: 1.4.2");
    assertThat(new BuildInfoStatusProvider(build("1.4.2"), new GitProperties(new Properties())).status()).isEqualTo("Version: 1.4.2");
  }

  @Test
  void reportsOnlyTheCommitWithoutAVersion() {
    assertThat(new BuildInfoStatusProvider(null, git("commit.id", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678")).status()).isEqualTo("Version: a1b2c3d");
    assertThat(new BuildInfoStatusProvider(new BuildProperties(new Properties()), git("commit.id", "a1b2c3d")).status()).isEqualTo("Version: a1b2c3d");
  }

  @Test
  void reportsNothingWithoutBuildInformation() {
    assertThat(new BuildInfoStatusProvider(null, null).status()).isEmpty();
    assertThat(new BuildInfoStatusProvider(new BuildProperties(new Properties()), new GitProperties(new Properties())).status()).isEmpty();
  }

  @Test
  void leavesOutABlankVersion() {
    assertThat(new BuildInfoStatusProvider(build(" "), null).status()).isEmpty();
  }
}
