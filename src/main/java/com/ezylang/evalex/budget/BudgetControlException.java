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
 * Common base class for budget related control flow during evaluation.
 *
 * <p>These exceptions are unchecked on purpose: they can be raised on every evaluation path,
 * including inside custom {@code DataAccessorIfc} implementations whose interface must not change.
 * They are distinct from the checked {@code EvaluationException} used for ordinary evaluation
 * errors.
 *
 * <p>Diagnostic data only references expression syntax (token text and positions), call frame
 * names, consumed counts and configured limits. Evaluated data values are never included.
 */
public abstract class BudgetControlException extends RuntimeException {

  private final String tokenString;
  private final int startPosition;
  private final int endPosition;
  private final List<CallFrame> callChain;
  private final Map<BudgetCategory, Long> consumed;
  private final ResourceBudget budget;

  /**
   * Creates a new budget control exception.
   *
   * @param message The diagnostic message without data values.
   * @param tokenString The token text at the current AST position, or {@code null}.
   * @param startPosition The zero based start position of the token, or -1.
   * @param endPosition The end position of the token, or -1.
   * @param callChain A snapshot of the current function and operator call chain.
   * @param consumed A snapshot of consumed units per category.
   * @param budget The configured budget.
   */
  protected BudgetControlException(
      String message,
      String tokenString,
      int startPosition,
      int endPosition,
      List<CallFrame> callChain,
      Map<BudgetCategory, Long> consumed,
      ResourceBudget budget) {
    super(message);
    this.tokenString = tokenString;
    this.startPosition = startPosition;
    this.endPosition = endPosition;
    this.callChain = callChain;
    this.consumed = consumed;
    this.budget = budget;
  }

  /**
   * @return The token text at the AST position where the limit was detected, or {@code null} if no
   *     token was active.
   */
  public String getTokenString() {
    return tokenString;
  }

  /**
   * @return The zero based start position of the current token, or -1 if unknown.
   */
  public int getStartPosition() {
    return startPosition;
  }

  /**
   * @return The end position of the current token, or -1 if unknown.
   */
  public int getEndPosition() {
    return endPosition;
  }

  /**
   * @return An unmodifiable snapshot of the active function and operator call chain, innermost
   *     first.
   */
  public List<CallFrame> getCallChain() {
    return callChain;
  }

  /**
   * @return An unmodifiable snapshot of consumed units per category when the exception was raised.
   */
  public Map<BudgetCategory, Long> getConsumed() {
    return consumed;
  }

  /**
   * @return The configured budget.
   */
  public ResourceBudget getBudget() {
    return budget;
  }
}
