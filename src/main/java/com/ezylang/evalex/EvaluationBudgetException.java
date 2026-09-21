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
package com.ezylang.evalex;

import com.ezylang.evalex.budget.BudgetCategory;
import com.ezylang.evalex.parser.Token;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Thrown when an evaluation exceeds its configured {@link
 * com.ezylang.evalex.budget.ResourceBudget}. The exception is an {@link EvaluationException}, so
 * existing callers that catch {@link EvaluationException} continue to work, but callers can
 * additionally distinguish budget exhaustion from ordinary evaluation errors.
 *
 * <p>The exception exposes the current AST position through the inherited token properties and the
 * active function or operator chain. It reports limits and consumed category counts, but never data
 * values.
 */
@Getter
public class EvaluationBudgetException extends EvaluationException {

  /** The category that triggered the limit, or {@code null} for the total weight limit. */
  private final BudgetCategory exceededCategory;

  /** Snapshot of consumed counts per category at the time the limit was exceeded. */
  private final Map<BudgetCategory, Long> consumedByCategory;

  /** Total consumed weight at the time the limit was exceeded. */
  private final long consumedTotalWeight;

  /** Configured per category limits at the time the limit was exceeded. */
  private final Map<BudgetCategory, Long> categoryLimits;

  /** Configured total weight limit, or {@code -1} when not configured. */
  private final long totalWeightLimit;

  /** Function or operator names forming the active call chain, outermost first. */
  private final List<String> callChain;

  /**
   * Creates a budget exceeded exception.
   *
   * @param token The token at which the limit was exceeded, may be {@code null} for charges made
   *     outside AST evaluation.
   * @param exceededCategory The exceeded category, or {@code null} if the total weight limit was
   *     exceeded.
   * @param consumedTotalWeight The total weight consumed so far.
   * @param consumedByCategory Consumed counts per category.
   * @param totalWeightLimit The configured total weight limit.
   * @param categoryLimits The configured per category limits.
   * @param callChain The active function or operator call chain.
   */
  public EvaluationBudgetException(
      Token token,
      BudgetCategory exceededCategory,
      long consumedTotalWeight,
      Map<BudgetCategory, Long> consumedByCategory,
      long totalWeightLimit,
      Map<BudgetCategory, Long> categoryLimits,
      List<String> callChain) {
    super(token, buildMessage(exceededCategory, consumedTotalWeight, totalWeightLimit, callChain));
    this.exceededCategory = exceededCategory;
    this.consumedTotalWeight = consumedTotalWeight;
    this.consumedByCategory = consumedByCategory;
    this.totalWeightLimit = totalWeightLimit;
    this.categoryLimits = categoryLimits;
    this.callChain = callChain;
  }

  private static String buildMessage(
      BudgetCategory exceededCategory,
      long consumedTotalWeight,
      long totalWeightLimit,
      List<String> callChain) {
    String limitDescription =
        exceededCategory == null
            ? String.format("total weight limit of %d", totalWeightLimit)
            : String.format(
                "limit for %s", exceededCategory.name().replace('_', ' ').toLowerCase());
    StringBuilder message =
        new StringBuilder(
            String.format(
                "Resource budget exceeded: consumed total weight %d, exceeded %s",
                consumedTotalWeight, limitDescription));
    if (callChain != null && !callChain.isEmpty()) {
      message.append(", call chain: ").append(String.join(" -> ", callChain));
    }
    return message.toString();
  }
}
