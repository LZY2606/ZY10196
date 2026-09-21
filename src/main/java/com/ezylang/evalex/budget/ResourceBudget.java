/*
  Copyright 2012-2026 Udo Klimaschewski

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/
package com.ezylang.evalex.budget;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable limits for the work performed during a single expression evaluation.
 *
 * <p>Two kinds of limits can be configured independently and are enforced together:
 *
 * <ul>
 *   <li>A per-category limit, e.g. at most 1000 {@link BudgetCategory#FUNCTION_CALL}s.
 *   <li>A total weight limit. Every unit of consumed work adds the weight configured for its
 *       category ({@link Builder#weight(BudgetCategory, long)}) to the total weight. This allows a
 *       single budget to cover all categories with a shared cap.
 * </ul>
 *
 * <p>A negative limit or weight disables that limit. The default budget has every limit disabled,
 * so evaluation behaves exactly as without a budget.
 *
 * <p>Instances are immutable and thread-safe. The mutable consumption state lives in {@link
 * EvaluationContext} and is never shared between evaluations.
 */
public final class ResourceBudget {

  /** Sentinel value meaning "no limit". */
  public static final long UNLIMITED = -1L;

  /** A budget without any limits, this is the default. */
  public static final ResourceBudget UNBOUNDED = builder().build();

  private final long[] limits = new long[BudgetCategory.values().length];
  private final long[] weights = new long[BudgetCategory.values().length];
  private final long totalWeightLimit;

  private ResourceBudget(Builder builder) {
    for (BudgetCategory category : BudgetCategory.values()) {
      long limit = builder.limits.getOrDefault(category, UNLIMITED);
      long weight = builder.weights.getOrDefault(category, 1L);
      limits[category.ordinal()] = limit;
      weights[category.ordinal()] = weight;
    }
    totalWeightLimit = builder.totalWeightLimit;
  }

  /**
   * Creates a new builder.
   *
   * @return A builder with all limits disabled and every weight set to 1.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the limit for a category.
   *
   * @param category The category.
   * @return The maximum number of consumed units, or {@link #UNLIMITED} if unlimited.
   */
  public long getLimit(BudgetCategory category) {
    return limits[category.ordinal()];
  }

  /**
   * Returns the weight per consumed unit of a category.
   *
   * @param category The category.
   * @return The weight added to the total weight per consumed unit, always at least 1.
   */
  public long getWeight(BudgetCategory category) {
    return weights[category.ordinal()];
  }

  /**
   * Returns the limit for the summed weight of all categories.
   *
   * @return The maximum total weight, or {@link #UNLIMITED} if unlimited.
   */
  public long getTotalWeightLimit() {
    return totalWeightLimit;
  }

  /**
   * Returns an unmodifiable map of all configured category limits.
   *
   * @return The per-category limits.
   */
  public Map<BudgetCategory, Long> getLimits() {
    EnumMap<BudgetCategory, Long> map = new EnumMap<>(BudgetCategory.class);
    for (BudgetCategory category : BudgetCategory.values()) {
      map.put(category, limits[category.ordinal()]);
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Returns an unmodifiable map of all configured category weights.
   *
   * @return The per-unit weights.
   */
  public Map<BudgetCategory, Long> getWeights() {
    EnumMap<BudgetCategory, Long> map = new EnumMap<>(BudgetCategory.class);
    for (BudgetCategory category : BudgetCategory.values()) {
      map.put(category, weights[category.ordinal()]);
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Checks whether any limit is configured.
   *
   * @return {@code true} if at least one category limit or the total weight limit is enabled.
   */
  public boolean isBounded() {
    if (totalWeightLimit != UNLIMITED) {
      return true;
    }
    for (long limit : limits) {
      if (limit != UNLIMITED) {
        return true;
      }
    }
    return false;
  }

  /** Builder for {@link ResourceBudget}. */
  public static final class Builder {

    private final EnumMap<BudgetCategory, Long> limits = new EnumMap<>(BudgetCategory.class);
    private final EnumMap<BudgetCategory, Long> weights = new EnumMap<>(BudgetCategory.class);
    private long totalWeightLimit = UNLIMITED;

    private Builder() {}

    /**
     * Sets the limit for one category.
     *
     * @param category The category to limit.
     * @param limit The maximum number of consumed units, must be positive.
     * @return This builder.
     */
    public Builder limit(BudgetCategory category, long limit) {
      Objects.requireNonNull(category, "category");
      if (limit <= 0) {
        throw new IllegalArgumentException("Limit must be positive, use builder() for unlimited");
      }
      limits.put(category, limit);
      return this;
    }

    /**
     * Sets the weight per consumed unit of one category. The weight is only used together with
     * {@link #totalWeightLimit(long)}.
     *
     * @param category The category whose weight is set.
     * @param weight The weight per unit, must be positive.
     * @return This builder.
     */
    public Builder weight(BudgetCategory category, long weight) {
      Objects.requireNonNull(category, "category");
      if (weight <= 0) {
        throw new IllegalArgumentException("Weight must be positive");
      }
      weights.put(category, weight);
      return this;
    }

    /**
     * Sets the limit for the summed weight of all categories.
     *
     * @param totalWeightLimit The maximum total weight, must be positive.
     * @return This builder.
     */
    public Builder totalWeightLimit(long totalWeightLimit) {
      if (totalWeightLimit <= 0) {
        throw new IllegalArgumentException("Total weight limit must be positive");
      }
      this.totalWeightLimit = totalWeightLimit;
      return this;
    }

    /**
     * Builds the immutable budget.
     *
     * @return The new {@link ResourceBudget}.
     */
    public ResourceBudget build() {
      return new ResourceBudget(this);
    }
  }
}
