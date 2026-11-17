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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClassLoaderStatusProvider}.
 *
 * @author Joao Silva
 */
class ClassLoaderStatusProviderTest {

  @Test
  void reportsTheLoadedClassesRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    // under an Arabic (Egypt) locale, numbers are formatted with Arabic-Indic digits, such as "١٥" for 15
    Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(new ClassLoaderStatusProvider().status()).matches("Classes: [0-9]+");
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }
}
