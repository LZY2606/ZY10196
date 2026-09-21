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

import static org.assertj.core.api.Assertions.assertThat;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ParseException;
import org.junit.jupiter.api.Test;

class LazyEvaluationBudgetTest {

  @Test
  void testShortCircuitAndDoesNotVisitRightBranch() throws EvaluationException, ParseException {
    Expression expression = new Expression("1 > 2 && 1 / 0 == 0");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT))
        .as("division node in unvisited branch must not be counted")
        .isEqualTo(4L);
  }

  @Test
  void testShortCircuitOrDoesNotVisitRightBranch() throws EvaluationException, ParseException {
    Expression expression = new Expression("1 < 2 || 1 / 0 == 0");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(4L);
  }

  @Test
  void testShortCircuitVisitsBothBranchesWhenNeeded() throws EvaluationException, ParseException {
    Expression expression = new Expression("1 < 2 && 2 < 3");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(7L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(2L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }

  @Test
  void testIfOnlyVisitsSelectedBranch() throws EvaluationException, ParseException {
    Expression expression = new Expression("IF(1 > 0, 1, 1 / 0)");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    EvaluationValue result = expression.evaluate(context);

    assertThat(result.getNumberValue()).isEqualByComparingTo("1");
    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }

  @Test
  void testIfFalseBranchCountedWhenConditionFalse() throws EvaluationException, ParseException {
    Expression expression = new Expression("IF(1 > 9, 1 / 0, 7)");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThat(expression.evaluate(context).getNumberValue()).isEqualByComparingTo("7");
    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
  }

  @Test
  void testUnvisitedIfBranchCanExceedNodeBudgetWithoutFailure()
      throws EvaluationException, ParseException {
    Expression expression = new Expression("IF(1 > 0, 1, 1 + 2 + 3 + 4 + 5)");
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 10L).build();

    assertThat(expression.evaluate(budget).getNumberValue()).isEqualByComparingTo("1");
  }

  @Test
  void testEachSyntacticLazyOperandIsEvaluatedAtMostOnce()
      throws EvaluationException, ParseException {
    // Each operand node appears once in the syntax tree and is evaluated at most once;
    // repeated reads of the same node are covered by the custom operator test.
    Expression expression = new Expression("(1 < 2 && 2 < 3) || (3 < 4 && 4 < 5)");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(3L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }

  @Test
  void testSwitchEvaluatesOnlyMatchedResult() throws EvaluationException, ParseException {
    Expression expression = new Expression("SWITCH(2, 1, 10, 2, 20, 30)");
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThat(expression.evaluate(context).getNumberValue()).isEqualByComparingTo("20");
    // matching value 2 and matched result 20 are evaluated lazily
    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(2L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }
}
