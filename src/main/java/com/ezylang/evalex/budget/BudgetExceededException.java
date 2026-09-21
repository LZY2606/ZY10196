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

import java.util.List;
import java.util.Map;

/**
 * Thrown when an evaluation consumes more work than allowed by its {@link ResourceBudget}.
 *
 * <p>This is an unchecked exception distinct from ordinary checked {@code EvaluationException}s,
 * see {@link BudgetControlException} for the rationale. The message and accessors include the
 * current AST position, the active call chain, the consumed units per category and the configured
 * limits, but never any evaluated data value.
 */
public class BudgetExceededException extends BudgetControlException {

  private final BudgetCategory category;
  private final long limit;
  private final long consumedBeforeRequest;
  private final long requestedUnits;

  /**
   * Creates a new exception for an exceeded per-category limit.
   *
   * @param category The category whose limit was exceeded.
   * @param limit The configured limit.
   * @param consumedBeforeRequest The consumed units in the category before the rejected request.
   * @param requestedUnits The units requested by the rejected operation.
   * @param tokenString The current token text.
   * @param startPosition The current token start position.
   * @param endPosition The current token end position.
   * @param callChain A snapshot of the active call chain.
   * @param consumed A snapshot of consumed units per category.
   * @param budget The configured budget.
   */
  public BudgetExceededException(
      BudgetCategory category,
      long limit,
      long consumedBeforeRequest,
      long requestedUnits,
      String tokenString,
      int startPosition,
      int endPosition,
      List<CallFrame> callChain,
      Map<BudgetCategory, Long> consumed,
      ResourceBudget budget) {
    super(
        buildCategoryMessage(
            category, limit, consumedBeforeRequest, requestedUnits, tokenString, startPosition),
        tokenString,
        startPosition,
        endPosition,
        callChain,
        consumed,
        budget);
    this.category = category;
    this.limit = limit;
    this.consumedBeforeRequest = consumedBeforeRequest;
    this.requestedUnits = requestedUnits;
  }

  /**
   * Creates a new exception for an exceeded total weight limit.
   *
   * @param limit The configured total weight limit.
   * @param consumedBeforeRequest The consumed total weight before the rejected request.
   * @param requestedWeight The weight requested by the rejected operation.
   * @param tokenString The current token text.
   * @param startPosition The current token start position.
   * @param endPosition The current token end position.
   * @param callChain A snapshot of the active call chain.
   * @param consumed A snapshot of consumed units per category.
   * @param budget The configured budget.
   */
  public BudgetExceededException(
      long limit,
      long consumedBeforeRequest,
      long requestedWeight,
      String tokenString,
      int startPosition,
      int endPosition,
      List<CallFrame> callChain,
      Map<BudgetCategory, Long> consumed,
      ResourceBudget budget) {
    super(
        buildTotalWeightMessage(
            limit, consumedBeforeRequest, requestedWeight, tokenString, startPosition),
        tokenString,
        startPosition,
        endPosition,
        callChain,
        consumed,
        budget);
    this.category = null;
    this.limit = limit;
    this.consumedBeforeRequest = consumedBeforeRequest;
    this.requestedUnits = requestedWeight;
  }

  /**
   * @return The exceeded category, or {@code null} if the total weight limit was exceeded.
   */
  public BudgetCategory getCategory() {
    return category;
  }

  /**
   * @return {@code true} if the total weight limit was exceeded rather than a category limit.
   */
  public boolean isTotalWeightLimit() {
    return category == null;
  }

  /**
   * @return The exceeded limit (units per category or total weight).
   */
  public long getLimit() {
    return limit;
  }

  /**
   * @return The consumed units (or total weight) before the rejected request.
   */
  public long getConsumedBeforeRequest() {
    return consumedBeforeRequest;
  }

  /**
   * @return The units or weight requested by the rejected operation.
   */
  public long getRequestedUnits() {
    return requestedUnits;
  }

  private static String buildCategoryMessage(
      BudgetCategory category,
      long limit,
      long consumedBeforeRequest,
      long requestedUnits,
      String tokenString,
      int startPosition) {
    return String.format(
        "Resource budget exceeded for %s: limit=%d, consumed=%d, requested=%d at position=%d,"
            + " token='%s'",
        category, limit, consumedBeforeRequest, requestedUnits, startPosition, tokenString);
  }

  private static String buildTotalWeightMessage(
      long limit,
      long consumedBeforeRequest,
      long requestedWeight,
      String tokenString,
      int startPosition) {
    return String.format(
        "Total resource weight budget exceeded: limit=%d, consumed=%d, requested=%d at position=%d,"
            + " token='%s'",
        limit, consumedBeforeRequest, requestedWeight, startPosition, tokenString);
  }
}
