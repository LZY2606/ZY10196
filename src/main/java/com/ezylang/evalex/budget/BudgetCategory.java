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

/**
 * Categories of work that can be limited by a {@link ResourceBudget}. Each visit of an AST node
 * during an evaluation is assigned to exactly one category.
 */
public enum BudgetCategory {

  /** A node of the expression AST is visited during eager evaluation. */
  AST_NODE_VISIT("AST node visit"),

  /** A built-in or custom function is invoked. */
  FUNCTION_CALL("function call"),

  /** A built-in or custom prefix, postfix or infix operator is invoked. */
  OPERATOR_CALL("operator call"),

  /** A lazy parameter (expression node) is evaluated for the first time in this evaluation. */
  LAZY_EVALUATION("lazy parameter evaluation"),

  /** A previously evaluated lazy parameter is read again and the cached result is used. */
  LAZY_CACHE_HIT("lazy parameter cache hit"),

  /** One element of an array or nested array structure is traversed. */
  COLLECTION_ELEMENT("collection element traversal"),

  /** A variable value is read from the configured {@code DataAccessorIfc}. */
  DATA_ACCESS("data accessor read");

  private final String description;

  BudgetCategory(String description) {
    this.description = description;
  }

  /**
   * Returns a human readable description of the category.
   *
   * @return The category description.
   */
  public String getDescription() {
    return description;
  }
}
