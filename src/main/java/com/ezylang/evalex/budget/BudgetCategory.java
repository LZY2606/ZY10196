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

/**
 * Categories of work that can be limited by a {@link ResourceBudget}.
 *
 * <p>Each category counts a distinct kind of evaluation work. Diagnostic data in {@link
 * BudgetExceededException} and {@link EvaluationContext#getConsumed()} uses these categories and
 * never reports evaluated data values, only counts and names taken from the expression syntax.
 */
public enum BudgetCategory {

  /**
   * Visits of AST nodes that are actually evaluated. Nodes in branches that are not visited, e.g.
   * the unselected branch of an {@code IF} function or a short-circuited {@code &&} / {@code ||}
   * operand, are not counted.
   */
  NODE_VISIT,

  /** Calls to a function, including built-in and custom functions. */
  FUNCTION_CALL,

  /** Calls to an operator, including built-in and custom operators. */
  OPERATOR_CALL,

  /**
   * First evaluation of a lazy parameter or lazy operand, i.e. an expression node passed to a lazy
   * function parameter or lazy operator that is evaluated through {@link
   * EvaluationContext#evaluateLazy}.
   */
  LAZY_EVALUATION,

  /**
   * Repeated read of a lazy parameter or lazy operand that was already evaluated in the current
   * evaluation. The cached result is returned and the node itself is not visited again.
   */
  LAZY_CACHE_HIT,

  /** Traversal of a single element while iterating over an array value. */
  COLLECTION_ELEMENT,

  /** Read of a variable through the configured {@code DataAccessorIfc}. */
  DATA_ACCESS
}
