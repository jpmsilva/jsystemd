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

import static java.util.Objects.requireNonNull;

import com.github.jpmsilva.jsystemd.StatusProvider;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Implementation of {@link StatusProvider} that provides the values of chosen Micrometer meters, such as
 * {@code hikaricp.connections.active: 7, Requests: 15234}, so that an application can show what its libraries measure without writing a status provider
 * for each of them.
 *
 * <p>Each meter to show is given by its name, optionally narrowed with tags, and is shown under a label, or under its name without one. Its value is the
 * one the metrics endpoint of Spring Boot Actuator shows for the same name and tags ({@code /actuator/metrics/<name>?tag=<key>:<value>}): when several
 * meters match, as a name usually has a meter for each combination of its tags, their measurements are added together. What is shown depends on the type
 * of the meter:
 * <ul>
 *   <li>gauges show their value;</li>
 *   <li>counters show their count;</li>
 *   <li>timers and distribution summaries show how many times they recorded, not the times or amounts recorded;</li>
 *   <li>long task timers show the tasks that are running;</li>
 *   <li>any other meter shows the first of those it measures, or is left out when it measures none of them.</li>
 * </ul>
 *
 * <p>A meter that does not exist is left out, rather than failing, as most meters are only registered once what they measure is first used, and so is a
 * meter without a value, such as a gauge of an object that is gone.
 *
 * <p>Whole numbers are shown as they are, and the others rounded to two decimal places; from a million on, numbers are shortened to a decimal place and a
 * suffix, such as {@code 1.5M}, {@code 2.3G} or {@code 4T}. Values measured in bytes are shown as the memory status providers show them, such as
 * {@code 133.2 MiB}.
 *
 * @author Joao Silva
 */
public class MicrometerStatusProvider implements StatusProvider {

  // what a meter shows, by order of preference: a meter of the built-in types only measures one of them
  private static final List<Statistic> SHOWN_STATISTICS = List.of(Statistic.VALUE, Statistic.COUNT, Statistic.ACTIVE_TASKS);

  private static final String BYTES = "bytes";

  private static final String[] BYTE_UNIT_PREFIXES = {"", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei"};

  private static final double MILLION = 1_000_000;

  private static final String[] SUFFIXES = {"M", "G", "T"};

  private final MeterRegistry registry;

  private final List<ShownMeter> meters;

  /**
   * Creates a new instance that shows the given meters of the given registry, in their order.
   *
   * @param registry the registry to find the meters in
   * @param meters the meters to show
   */
  public MicrometerStatusProvider(MeterRegistry registry, List<ShownMeter> meters) {
    this.registry = requireNonNull(registry, "Registry must not be null");
    this.meters = List.copyOf(requireNonNull(meters, "Meters must not be null"));
  }

  // for tests: the meters to show
  List<ShownMeter> meters() {
    return meters;
  }

  @Override
  public String status() {
    List<String> statuses = new ArrayList<>();
    for (ShownMeter meter : meters) {
      String value = value(meter);
      if (value != null) {
        statuses.add(meter.shownAs() + ": " + value);
      }
    }
    return String.join(", ", statuses);
  }

  /**
   * Returns the value to show for a meter, or <code>null</code> when there is none: no meter has its name and tags, or none of them has a value.
   */
  private @Nullable String value(ShownMeter shown) {
    List<Tag> tags = new ArrayList<>();
    shown.tags().forEach((key, value) -> tags.add(Tag.of(key, value)));
    Collection<Meter> found = registry.find(shown.name()).tags(tags).meters();
    // as the metrics endpoint of Spring Boot Actuator does: the measurements of all the meters are added together, by statistic
    Map<Statistic, Double> statistics = new EnumMap<>(Statistic.class);
    String unit = null;
    for (Meter meter : found) {
      for (Measurement measurement : meter.measure()) {
        double value = measurement.getValue();
        // a gauge has no value once the object it measures is gone
        if (Double.isFinite(value)) {
          statistics.merge(measurement.getStatistic(), value, measurement.getStatistic() == Statistic.MAX ? Math::max : Double::sum);
        }
      }
      if (unit == null) {
        unit = meter.getId().getBaseUnit();
      }
    }
    for (Statistic statistic : SHOWN_STATISTICS) {
      Double value = statistics.get(statistic);
      if (value != null) {
        // only the values are measured in the unit of the meter: the counts of a distribution summary of bytes, for instance, are not bytes
        return statistic != Statistic.COUNT && BYTES.equals(unit) ? formatBytes(value) : format(value);
      }
    }
    return null;
  }

  // regardless of the default locale, which could format the numbers with other digits or separators
  static String format(double value) {
    double magnitude = Math.abs(value);
    if (magnitude < MILLION) {
      return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    int index = 0;
    double scaled = value / MILLION;
    while (index < SUFFIXES.length - 1 && Math.abs(scaled) >= 1000) {
      scaled /= 1000;
      index++;
    }
    return BigDecimal.valueOf(scaled).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + SUFFIXES[index];
  }

  // the same format as the memory status providers of the core library, such as the HeapStatusProvider
  static String formatBytes(double value) {
    double magnitude = Math.abs(value);
    int index = 0;
    while (magnitude >= 1024 && index < BYTE_UNIT_PREFIXES.length - 1) {
      magnitude /= 1024;
      index++;
    }
    return String.format(Locale.ROOT, "%,.1f %sB", Math.copySign(magnitude, value), BYTE_UNIT_PREFIXES[index]);
  }

  /**
   * A meter to show in the status: the meters with a name, and with all of the given tags, shown under a label.
   *
   * @param name the name of the meter, such as <code>hikaricp.connections.active</code>
   * @param tags the tags the meter must have, by key, such as <code>pool</code> and <code>HikariPool-1</code>; none to take all the meters with the name
   *     into account
   * @param label the label the value is shown under, or <code>null</code> to show it under the name of the meter
   */
  public record ShownMeter(String name, Map<String, String> tags, @Nullable String label) {

    /**
     * Creates a meter to show. The tags are copied, keeping their order, and a blank label is the same as none.
     *
     * @param name the name of the meter
     * @param tags the tags the meter must have, by key
     * @param label the label the value is shown under, or <code>null</code> to show it under the name of the meter
     * @throws NullPointerException if the name or the tags are null
     * @throws IllegalArgumentException if the name is blank
     */
    public ShownMeter {
      if (requireNonNull(name, "Name must not be null").isBlank()) {
        throw new IllegalArgumentException("Name must not be blank");
      }
      tags = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(tags, "Tags must not be null")));
      label = label == null || label.isBlank() ? null : label;
    }

    /**
     * Creates a meter to show under its name, whatever its tags.
     *
     * @param name the name of the meter
     */
    public ShownMeter(String name) {
      this(name, Map.of(), null);
    }

    // the label, or the name without one
    String shownAs() {
      return label == null ? name : label;
    }
  }
}
