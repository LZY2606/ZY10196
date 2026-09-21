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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ezylang.evalex.EvaluationBudgetException;
import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ParseException;
import org.junit.jupiter.api.Test;

class ExpressionBudgetTest {

  @Test
  void expressionWithoutBudgetEvaluatesAsBefore() throws EvaluationException, ParseException {
    Expression expression = new Expression("a + b * 2").with("a", 1).and("b", 3);

    assertThat(expression.evaluate().getStringValue()).isEqualTo("7");
  }

  @Test
  void nodeVisitLimitStopsEvaluationAndReportsPosition() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.AST_NODE_VISIT, 3L).build();

    assertThatThrownBy(
            () -> new Expression("1 + 2 + 3 + 4").evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getExceededCategory())
                  .isEqualTo(BudgetCategory.AST_NODE_VISIT);
              assertThat(budgetException.getTokenString()).isNotBlank();
              assertThat(budgetException.getStartPosition()).isPositive();
              assertThat(budgetException.getConsumedByCategory())
                  .containsEntry(BudgetCategory.AST_NODE_VISIT, 4L);
            });
  }

  @Test
  void functionCallLimitStopsEvaluationWithCallChain() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, 1L).build();

    assertThatThrownBy(
            () -> new Expression("SQRT(ABS(-1))").evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getExceededCategory())
                  .isEqualTo(BudgetCategory.FUNCTION_CALL);
              assertThat(budgetException.getMessage()).contains("function SQRT");
            });
  }

  @Test
  void operatorCallLimitStopsEvaluation() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.OPERATOR_CALL, 1L).build();

    assertThatThrownBy(() -> new Expression("1 + 2 + 3").evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.OPERATOR_CALL));
  }

  @Test
  void dataAccessLimitCountsAccessorReadsButNotConstants() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.DATA_ACCESS, 1L).build();

    assertThatThrownBy(
            () ->
                new Expression("a + b + PI")
                    .with("a", 1)
                    .and("b", 2)
                    .evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.DATA_ACCESS));
  }

  @Test
  void totalWeightCanLimitMixedWork() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(5L).build();

    assertThatThrownBy(
            () -> new Expression("1 + 2 + 3 + 4").evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getExceededCategory()).isNull();
              assertThat(budgetException.getTotalWeightLimit()).isEqualTo(5L);
              assertThat(budgetException.getConsumedTotalWeight()).isEqualTo(6L);
            });
  }

  @Test
  void budgetConfiguredOnConfigurationAppliesToEvaluate() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, 0L).build();
    ExpressionConfiguration configuration =
        ExpressionConfiguration.builder().resourceBudget(budget).build();

    assertThatThrownBy(() -> new Expression("SQRT(4)", configuration).evaluate())
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.FUNCTION_CALL));
  }

  @Test
  void successfulEvaluationUnderBudgetLeavesContextConsumed()
      throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    new Expression("1 + 2").evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isEqualTo(3L);
    assertThat(context.getConsumedCount(BudgetCategory.OPERATOR_CALL)).isEqualTo(1L);
    assertThat(context.getConsumedTotalWeight()).isEqualTo(4L);
  }

  @Test
  void exceptionIsStillAnEvaluationException() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.AST_NODE_VISIT, 0L).build();

    assertThatThrownBy(() -> new Expression("1").evaluate(new EvaluationContext(budget)))
        .isInstanceOf(EvaluationException.class)
        .isInstanceOf(EvaluationBudgetException.class);
  }

  @Test
  void configuredLimitedBudgetAndUnlimitedConfigurationBehaveAsExpected()
      throws EvaluationException, ParseException {
    ExpressionConfiguration unlimited =
        ExpressionConfiguration.builder().resourceBudget(ResourceBudget.UNLIMITED_BUDGET).build();
    assertThat(new Expression("1 + 2", unlimited).evaluate().getStringValue()).isEqualTo("3");
  }

  @Test
  void nullContextIsRejected() {
    assertThatThrownBy(() -> new Expression("1 + 2").evaluate(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void currentContextIsAvailableToCustomCodeDuringEvaluation()
      throws EvaluationException, ParseException {
    EvaluationContext[] seen = new EvaluationContext[1];
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(
                java.util.Map.entry(
                    "INSPECT",
                    new com.ezylang.evalex.functions.AbstractFunction() {
                      @Override
                      public EvaluationValue evaluate(
                          Expression expression,
                          com.ezylang.evalex.parser.Token functionToken,
                          EvaluationValue... parameterValues) {
                        seen[0] = expression.getCurrentEvaluationContext();
                        return expression.convertValue(1);
                      }
                    }));
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    new Expression("INSPECT()", configuration).evaluate(context);

    assertThat(seen[0]).isSameAs(context);
  }

  @Test
  void successfulEvaluationWithLimitedConfigurationBudgetConsumesBudget()
      throws EvaluationException, ParseException {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(100L).build();
    ExpressionConfiguration configuration =
        ExpressionConfiguration.builder().resourceBudget(budget).build();

    assertThat(new Expression("1 + 2", configuration).evaluate().getStringValue()).isEqualTo("3");
  }

  @Test
  void explicitContextIsIgnoredWhenParentContextActive() throws Exception {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(
                java.util.Map.entry(
                    "USE_CONTEXT",
                    new com.ezylang.evalex.functions.AbstractFunction() {
                      @Override
                      public EvaluationValue evaluate(
                          Expression expression,
                          com.ezylang.evalex.parser.Token functionToken,
                          EvaluationValue... parameterValues)
                          throws EvaluationException {
                        EvaluationContext tiny =
                            new EvaluationContext(
                                ResourceBudget.builder().totalWeightLimit(0L).build());
                        try {
                          // Tiny context is ignored while the outer context is active.
                          return new Expression("1 + 2").evaluate(tiny);
                        } catch (ParseException e) {
                          throw new EvaluationException(functionToken, e.getMessage());
                        }
                      }
                    }));
    EvaluationContext outer =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(1_000L).build());

    EvaluationValue result = new Expression("USE_CONTEXT()", configuration).evaluate(outer);

    assertThat(result.getNumberValue().intValue()).isEqualTo(3);
  }
}
