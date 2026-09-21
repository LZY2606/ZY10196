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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ParseException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpressionBudgetTest {

  private ResourceBudget nodeLimit(long limit) {
    return ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, limit).build();
  }

  @Test
  void testUnboundedBudgetKeepsCurrentBehavior() {
    Expression expression = new Expression("1 + 2 * 3");

    assertThatCode(() -> expression.evaluate(ResourceBudget.UNBOUNDED)).doesNotThrowAnyException();
  }

  @Test
  void testNodeVisitLimitInterruptsEvaluation() {
    Expression expression = new Expression("1 + 2 + 3 + 4 + 5");
    ResourceBudget budget = nodeLimit(4L);

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getCategory()).isEqualTo(BudgetCategory.NODE_VISIT);
              assertThat(exception.getLimit()).isEqualTo(4L);
              assertThat(exception.getConsumedBeforeRequest()).isEqualTo(4L);
              assertThat(exception.getStartPosition()).isGreaterThanOrEqualTo(0);
              assertThat(exception.getTokenString()).isNotBlank();
              assertThat(exception.getMessage()).contains("NODE_VISIT");
            });
  }

  @Test
  void testNodeLimitExactlySufficientSucceeds() throws EvaluationException, ParseException {
    // 5 numbers and 4 additions = 9 nodes
    Expression expression = new Expression("1 + 2 + 3 + 4 + 5");

    EvaluationValue result = expression.evaluate(nodeLimit(9L));

    assertThat(result.getNumberValue()).isEqualByComparingTo("15");
  }

  @Test
  void testFunctionCallLimit() {
    Expression expression = new Expression("ABS(ABS(ABS(1)))");
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 2L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception ->
                assertThat(exception.getCategory()).isEqualTo(BudgetCategory.FUNCTION_CALL));
  }

  @Test
  void testOperatorCallLimit() {
    Expression expression = new Expression("1 + 2 + 3");
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.OPERATOR_CALL, 1L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getCategory()).isEqualTo(BudgetCategory.OPERATOR_CALL);
              assertThat(exception.getCallChain()).isNotEmpty();
            });
  }

  @Test
  void testDataAccessLimit() throws EvaluationException, ParseException {
    Expression expression = new Expression("a + b + c");
    expression.with("a", 1).with("b", 2).with("c", 3);
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.DATA_ACCESS, 2L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> assertThat(exception.getCategory()).isEqualTo(BudgetCategory.DATA_ACCESS));
  }

  @Test
  void testConstantsDoNotConsumeDataAccess() throws EvaluationException, ParseException {
    Expression expression = new Expression("PI + E");
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.DATA_ACCESS, 1L).build();

    assertThatCode(() -> expression.evaluate(budget)).doesNotThrowAnyException();
  }

  @Test
  void testCollectionElementLimitOnArrayIndex() {
    Expression expression = new Expression("a[0] + a[1]");
    expression.with("a", List.of(1, 2, 3));
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.COLLECTION_ELEMENT, 1L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception ->
                assertThat(exception.getCategory()).isEqualTo(BudgetCategory.COLLECTION_ELEMENT));
  }

  @Test
  void testArrayIndexTraversalIsCounted() throws EvaluationException, ParseException {
    Expression expression = new Expression("a[2]");
    expression.with("a", List.of(1, 2, 3));
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(1L);
  }

  @Test
  void testCollectionElementLimitOnSum() {
    Expression expression = new Expression("SUM(a)");
    expression.with("a", List.of(1, 2, 3, 4));
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.COLLECTION_ELEMENT, 2L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception ->
                assertThat(exception.getCategory()).isEqualTo(BudgetCategory.COLLECTION_ELEMENT));
  }

  @Test
  void testCollectionElementLimitOnMinMax() {
    Expression expression = new Expression("MAX(a)");
    expression.with("a", List.of(1, 2, 3, 4));
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.COLLECTION_ELEMENT, 3L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOf(BudgetExceededException.class);
  }

  @Test
  void testTotalWeightAcrossCategories() throws EvaluationException, ParseException {
    Expression expression = new Expression("a + b");
    expression.with("a", 1).with("b", 2);
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(6L).build();

    assertThatCode(() -> expression.evaluate(budget)).doesNotThrowAnyException();

    ResourceBudget tooSmall = ResourceBudget.builder().totalWeightLimit(5L).build();
    assertThatThrownBy(() -> expression.evaluate(tooSmall))
        .isInstanceOfSatisfying(
            BudgetExceededException.class, BudgetExceededException::isTotalWeightLimit);
  }

  @Test
  void testBudgetFromConfiguration() {
    ResourceBudget budget = nodeLimit(2L);
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration().toBuilder().resourceBudget(budget).build();
    Expression expression = new Expression("1 + 2 + 3", configuration);

    assertThatThrownBy(expression::evaluate).isInstanceOf(BudgetExceededException.class);
  }

  @Test
  void testDefaultConfigurationHasUnboundedBudget() {
    assertThat(ExpressionConfiguration.defaultConfiguration().getResourceBudget().isBounded())
        .isFalse();
  }

  @Test
  void testExceptionDoesNotLeakVariableValues() {
    Expression expression = new Expression("secret + other");
    expression.with("secret", "super-secret-value").with("other", 1);
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getMessage()).doesNotContain("super-secret-value");
              assertThat(exception.getConsumed().toString()).doesNotContain("super-secret");
            });
  }

  @Test
  void testContextAvailableAfterSuccessfulEvaluation() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 100L).build());
    Expression expression = new Expression("1 + 2");
    expression.evaluate(context);

    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(3L);
    assertThat(context.getConsumed(BudgetCategory.OPERATOR_CALL)).isEqualTo(1L);
    assertThat(EvaluationContext.getCurrent()).isNull();
  }

  @Test
  void testBigVariableArrayIterationIsBounded() {
    Expression expression = new Expression("SUM(a)");
    expression.with(
        "a",
        new java.util.ArrayList<BigDecimal>(
            java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(BigDecimal::valueOf)
                .collect(java.util.stream.Collectors.toList())));
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.COLLECTION_ELEMENT, 100L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception ->
                assertThat(exception.getConsumed().get(BudgetCategory.COLLECTION_ELEMENT))
                    .isEqualTo(100L));
  }

  @Test
  void testExplicitContextNestedEvaluationJoinsParent() throws EvaluationException, ParseException {
    EvaluationContext parent =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 10L).build());
    Expression outer = new Expression("child");
    outer.with(
        "child", EvaluationValue.expressionNodeValue(outer.createExpressionNode("1 + 2 + 3")));

    parent.activate();
    try {
      // An explicit context given from within an active evaluation is ignored.
      EvaluationContext child =
          new EvaluationContext(
              ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build());
      assertThat(outer.evaluate(child).getNumberValue()).isEqualByComparingTo("6");
    } finally {
      parent.close();
    }
    assertThat(parent.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(6L);
  }

  @Test
  void testExplicitBudgetNestedEvaluationJoinsParent() throws EvaluationException, ParseException {
    EvaluationContext parent = new EvaluationContext(ResourceBudget.UNBOUNDED);
    Expression outer = new Expression("child");
    outer.with("child", EvaluationValue.expressionNodeValue(outer.createExpressionNode("1 + 2")));

    parent.activate();
    try {
      assertThat(outer.evaluate(nodeLimit(1L)).getNumberValue()).isEqualByComparingTo("3");
    } finally {
      parent.close();
    }
    assertThat(parent.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(4L);
  }

  @Test
  void testEvaluateLazyParameterOutsideEvaluationStillEvaluates()
      throws EvaluationException, ParseException {
    Expression expression = new Expression("a");

    EvaluationValue value =
        expression.evaluateLazyParameter(expression.createExpressionNode("6/2"));

    assertThat(value.getNumberValue()).isEqualByComparingTo("3");
  }

  @Test
  void testEvaluateSubtreeOutsideEvaluationIsIndependent()
      throws EvaluationException, ParseException {
    Expression expression = new Expression("1 + 2");

    EvaluationValue value = expression.evaluateSubtree(expression.getAbstractSyntaxTree());

    assertThat(value.getNumberValue()).isEqualByComparingTo("3");
    assertThat(EvaluationContext.getCurrent()).isNull();
  }

  @Test
  void testOperatorFrameIsRemovedWhenOperandEvaluationFails() {
    // 10 additions: the failure happens while an inner operand of an outer + is evaluated.
    Expression expression = new Expression("1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10");
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.OPERATOR_CALL, 5L).build());

    assertThatThrownBy(() -> expression.evaluate(context))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception ->
                assertThat(exception.getCallChain())
                    .as("frame of the failed operator must be popped")
                    .hasSizeLessThanOrEqualTo(5));
  }
}
