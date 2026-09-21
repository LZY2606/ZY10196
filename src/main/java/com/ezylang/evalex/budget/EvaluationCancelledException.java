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
 * Thrown when an evaluation is cancelled through {@link EvaluationContext#cancel()}.
 *
 * <p>Cancellation is cooperative. It is distinct from a {@link BudgetExceededException} and from
 * ordinary checked {@code EvaluationException}s. Like other {@link BudgetControlException}s this
 * exception is unchecked and its diagnostic data never includes evaluated data values.
 */
public class EvaluationCancelledException extends BudgetControlException {

  /**
   * Creates a new cancellation exception.
   *
   * @param tokenString The current token text.
   * @param startPosition The current token start position.
   * @param endPosition The current token end position.
   * @param callChain A snapshot of the active call chain.
   * @param consumed A snapshot of consumed units per category.
   * @param budget The configured budget.
   */
  public EvaluationCancelledException(
      String tokenString,
      int startPosition,
      int endPosition,
      List<CallFrame> callChain,
      Map<BudgetCategory, Long> consumed,
      ResourceBudget budget) {
    super(
        String.format(
            "Evaluation was cancelled at position=%d, token='%s'", startPosition, tokenString),
        tokenString,
        startPosition,
        endPosition,
        callChain,
        consumed,
        budget);
  }
}
