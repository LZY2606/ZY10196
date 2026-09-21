/*
  Copyright 2012-2025 Udo Klimaschewski

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
import java.util.HashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * Immutable resource budget configuration for a single expression evaluation.
 *
 * <p>A budget can limit the total weight of all work and, optionally, the number of counted events
 * per {@link BudgetCategory}. Every counted event adds a weight of one to the total weight. A limit
 * of {@link #UNLIMITED} means that no limit is enforced.
 *
 * <p>A budget without any limits is an <em>unlimited</em> budget. Evaluations started without a
 * configured budget keep the legacy behavior and do not create an {@link EvaluationContext}.
 */
@Value
@Builder(toBuilder = true)
public class ResourceBudget {

  /** Value used to indicate that a limit is not enforced. */
  public static final long UNLIMITED = -1L;

  /** A shared budget instance without any limits. */
  public static final ResourceBudget UNLIMITED_BUDGET = ResourceBudget.builder().build();

  /** Maximum total weight of all counted events, or {@link #UNLIMITED}. */
  @Builder.Default long totalWeightLimit = UNLIMITED;

  /** Per category limits. Missing categories and {@link #UNLIMITED} values are not limited. */
  @Singular Map<BudgetCategory, Long> categoryLimits;

  /**
   * Creates a budget and validates all limit values. Used by the Lombok generated builder.
   *
   * @param totalWeightLimit The maximum total weight, must be {@link #UNLIMITED} or non-negative.
   * @param categoryLimits The per category limits, each value must be {@link #UNLIMITED} or
   *     non-negative. May be empty but not {@code null}.
   */
  public ResourceBudget(long totalWeightLimit, Map<BudgetCategory, Long> categoryLimits) {
    validateLimit("totalWeightLimit", totalWeightLimit);
    this.totalWeightLimit = totalWeightLimit;

    if (categoryLimits != null && new HashMap<>(categoryLimits).containsKey(null)) {
      throw new IllegalArgumentException("Budget category must not be null");
    }
    EnumMap<BudgetCategory, Long> copiedLimits = new EnumMap<>(BudgetCategory.class);
    if (categoryLimits != null) {
      for (Map.Entry<BudgetCategory, Long> entry : categoryLimits.entrySet()) {
        long limit = entry.getValue() == null ? UNLIMITED : entry.getValue();
        validateLimit("categoryLimit:" + entry.getKey(), limit);
        copiedLimits.put(entry.getKey(), limit);
      }
    }
    this.categoryLimits = Collections.unmodifiableMap(copiedLimits);
  }

  private static void validateLimit(String name, long limit) {
    if (limit != UNLIMITED && limit < 0) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid limit %d for '%s', limit must be -1 or non-negative", limit, name));
    }
  }

  /**
   * Gets the configured limit for a category.
   *
   * @param category The category to query.
   * @return The limit, or {@link #UNLIMITED} if no limit was configured for the category.
   */
  public long getCategoryLimit(BudgetCategory category) {
    return categoryLimits.getOrDefault(category, UNLIMITED);
  }

  /**
   * Checks if this budget enforces any limit.
   *
   * @return {@code true} if neither a total weight limit nor any category limit is configured.
   */
  public boolean isUnlimited() {
    return totalWeightLimit == UNLIMITED && categoryLimits.isEmpty();
  }
}
