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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ezylang.evalex.EvaluationBudgetException;
import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.parser.ParseException;
import org.junit.jupiter.api.Test;

class BudgetBoundaryTest {

  @Test
  void budgetExactlyExhaustedSucceedsAndOneMoreEventFails()
      throws EvaluationException, ParseException {
    // "1 + 2" consumes 3 node visits plus 1 operator call, total weight 4.
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(4L).build());

    assertThatCode(() -> new Expression("1 + 2").evaluate(context)).doesNotThrowAnyException();
    assertThat(context.getConsumedTotalWeight()).isEqualTo(4L);
  }

  @Test
  void oneWeightLessThanRequiredFailsAtBoundary() {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(3L).build());

    assertThatThrownBy(() -> new Expression("1 + 2").evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getConsumedTotalWeight())
                    .isEqualTo(4L));
  }

  @Test
  void zeroLimitFailsOnFirstNode() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.AST_NODE_VISIT, 0L).build());

    assertThatThrownBy(() -> new Expression("1").evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getConsumedByCategory())
                  .containsEntry(BudgetCategory.AST_NODE_VISIT, 1L);
            });
  }

  @Test
  void categoryLimitExactlyMetSucceeds() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.OPERATOR_CALL, 1L).build());

    assertThatCode(() -> new Expression("1 + 2").evaluate(context)).doesNotThrowAnyException();
    assertThat(context.getConsumedCount(BudgetCategory.OPERATOR_CALL)).isEqualTo(1L);
  }

  @Test
  void multipleCategoriesAreTrackedIndependently() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(1_000L).build());

    new Expression("SQRT(a) + ABS(b)").with("a", 16).and("b", -4).evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.FUNCTION_CALL)).isEqualTo(2L);
    assertThat(context.getConsumedCount(BudgetCategory.OPERATOR_CALL)).isEqualTo(1L);
    assertThat(context.getConsumedCount(BudgetCategory.DATA_ACCESS)).isEqualTo(2L);
    assertThat(context.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isEqualTo(5L);
  }

  @Test
  void evaluateSubtreeAfterMainEvaluationHasNoActiveContext()
      throws EvaluationException, ParseException {
    Expression expression = new Expression("1 + 2");
    expression.evaluate();

    assertThat(EvaluationContext.current().isActive()).isFalse();
    assertThatCode(() -> expression.evaluateSubtree(expression.getAbstractSyntaxTree()))
        .doesNotThrowAnyException();
  }
}
