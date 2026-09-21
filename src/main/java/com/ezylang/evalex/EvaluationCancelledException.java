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

import com.ezylang.evalex.parser.Token;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Thrown when an evaluation is cancelled through {@link
 * com.ezylang.evalex.budget.EvaluationContext#cancel(String)}. The exception is an {@link
 * EvaluationException}, so existing callers that catch {@link EvaluationException} continue to
 * work, while cancellation can also be handled explicitly and distinguished from budget exhaustion.
 *
 * <p>The exception exposes the current AST position through the inherited token properties, the
 * caller provided cancellation reason and the active function or operator chain. It never includes
 * data values.
 */
@Getter
public class EvaluationCancelledException extends EvaluationException {

  /** The reason provided when the evaluation was cancelled, may be {@code null}. */
  private final String cancellationReason;

  /** Snapshot of consumed counts per category at cancellation time. */
  private final Map<com.ezylang.evalex.budget.BudgetCategory, Long> consumedByCategory;

  /** Total consumed weight at cancellation time. */
  private final long consumedTotalWeight;

  /** Function or operator names forming the active call chain, outermost first. */
  private final List<String> callChain;

  /**
   * Creates a cancellation exception.
   *
   * @param token The token at which cancellation was observed, may be {@code null}.
   * @param cancellationReason The caller provided reason, may be {@code null}.
   * @param consumedTotalWeight The total weight consumed so far.
   * @param consumedByCategory Consumed counts per category.
   * @param callChain The active function or operator call chain.
   */
  public EvaluationCancelledException(
      Token token,
      String cancellationReason,
      long consumedTotalWeight,
      Map<com.ezylang.evalex.budget.BudgetCategory, Long> consumedByCategory,
      List<String> callChain) {
    super(token, buildMessage(cancellationReason, callChain));
    this.cancellationReason = cancellationReason;
    this.consumedTotalWeight = consumedTotalWeight;
    this.consumedByCategory = consumedByCategory;
    this.callChain = callChain;
  }

  private static String buildMessage(String cancellationReason, List<String> callChain) {
    StringBuilder message = new StringBuilder("Evaluation was cancelled");
    if (cancellationReason != null && !cancellationReason.isEmpty()) {
      message.append(": ").append(cancellationReason);
    }
    if (callChain != null && !callChain.isEmpty()) {
      message.append(", call chain: ").append(String.join(" -> ", callChain));
    }
    return message.toString();
  }
}
