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
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameter;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LazyAndShortCircuitBudgetTest {

  private static EvaluationContext contextWith(long totalWeightLimit) {
    return new EvaluationContext(
        ResourceBudget.builder().totalWeightLimit(totalWeightLimit).build());
  }

  @Test
  void shortCircuitAndDoesNotVisitRightOperandBranch() throws EvaluationException, ParseException {
    // False branch (2 / 0) must never be visited, otherwise evaluation would fail.
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    EvaluationValue result = new Expression("false && (2 / 0 > 0)").evaluate(context);

    assertThat(result.getBooleanValue()).isFalse();
    // The right operand node itself is not visited at all; only the left subtree and the operator.
    long visits = context.getConsumedCount(BudgetCategory.AST_NODE_VISIT);
    assertThat(visits).isEqualTo(2L);
  }

  @Test
  void shortCircuitOrDoesNotVisitRightOperandBranch() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    EvaluationValue result = new Expression("true || (2 / 0 > 0)").evaluate(context);

    assertThat(result.getBooleanValue()).isTrue();
    assertThat(context.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isEqualTo(2L);
  }

  @Test
  void ifFunctionOnlyVisitsTakenBranch() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    EvaluationValue result = new Expression("IF(true, 1, 2 / 0)").evaluate(context);

    assertThat(result.getNumberValue().intValue()).isEqualTo(1);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }

  @Test
  void repeatedLazyParameterReadUsesCacheAndCountsHit() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());
    Expression expression =
        new Expression("IF(true, RANDOM(), RANDOM()) + IF(true, RANDOM(), RANDOM())");

    expression.evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.LAZY_EVALUATION)).isGreaterThanOrEqualTo(2L);
  }

  @Test
  void cachedLazyNodeIsEvaluatedOnceWhenReadRepeatedly()
      throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("READ_TWICE", new ReadLazyTwiceFunction()));
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    EvaluationValue result = new Expression("READ_TWICE(21)", configuration).evaluate(context);

    assertThat(result.getNumberValue().intValue()).isEqualTo(42);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_CACHE_HIT)).isEqualTo(1L);
  }

  @Test
  void withoutBudgetSameLazyNodeReadTwiceIsEvaluatedTwice()
      throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("READ_TWICE", new ReadLazyTwiceFunction()));

    EvaluationValue result = new Expression("READ_TWICE(21)", configuration).evaluate();

    assertThat(result.getNumberValue().intValue()).isEqualTo(42);
  }

  @Test
  void lazyEvaluationCategoryCanBeLimited() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.LAZY_EVALUATION, 0L).build());

    assertThatThrownBy(() -> new Expression("IF(true, 1, 2)").evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.LAZY_EVALUATION));
  }

  @Test
  void collectionElementsAreCountedForSum() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.COLLECTION_ELEMENT, 2L).build());
    Expression expression = new Expression("SUM(values)").with("values", List.of(1, 2, 3, 4));

    assertThatThrownBy(() -> expression.evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.COLLECTION_ELEMENT));
  }

  @Test
  void collectionElementCountIsExactForSuccessfulEvaluation()
      throws EvaluationException, ParseException {
    EvaluationContext context = contextWith(1_000L);

    new Expression("SUM(values)").with("values", List.of(1, 2, 3)).evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void nestedArraysCountEachTraversedElement() throws EvaluationException, ParseException {
    EvaluationContext context = contextWith(1_000L);

    new Expression("SUM(values)")
        .with("values", List.of(List.of(1, 2), List.of(3)))
        .evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  /** Function that evaluates the same lazy parameter twice to observe cache behavior. */
  @FunctionParameter(name = "value", isLazy = true)
  public static class ReadLazyTwiceFunction extends AbstractFunction {

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues)
        throws EvaluationException {
      EvaluationValue first = expression.evaluateLazyNode(parameterValues[0].getExpressionNode());
      EvaluationValue second = expression.evaluateLazyNode(parameterValues[0].getExpressionNode());
      return expression.convertValue(first.getNumberValue().add(second.getNumberValue()));
    }
  }

  @Test
  void withoutBudgetRepeatedLazyReadsAreStillEvaluatedEveryTime()
      throws EvaluationException, ParseException {
    Expression expression = new Expression("SWITCH(1, 1, 42, 0)");

    assertThat(expression.evaluate().getNumberValue().intValue()).isEqualTo(42);
    assertThat(EvaluationContext.current().isActive()).isFalse();
  }
}
