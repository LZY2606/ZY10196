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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ASTNode;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import com.ezylang.evalex.parser.Token.TokenType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationContextTest {

  private EvaluationContext unboundedContext() {
    return new EvaluationContext(ResourceBudget.UNBOUNDED);
  }

  @Test
  void testRecordCountsConsumedUnits() {
    EvaluationContext context = unboundedContext();

    context.record(BudgetCategory.NODE_VISIT);
    context.record(BudgetCategory.NODE_VISIT, 3);
    context.record(BudgetCategory.FUNCTION_CALL);

    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(4L);
    assertThat(context.getConsumed(BudgetCategory.FUNCTION_CALL)).isEqualTo(1L);
    assertThat(context.getConsumedTotalWeight()).isEqualTo(5L);
  }

  @Test
  void testRecordRejectsNonPositiveUnits() {
    EvaluationContext context = unboundedContext();

    assertThatThrownBy(() -> context.record(BudgetCategory.NODE_VISIT, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void testCategoryLimitAllowsExactlyTheLimit() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 2L).build());

    context.record(BudgetCategory.FUNCTION_CALL);
    context.record(BudgetCategory.FUNCTION_CALL);

    assertThat(context.getConsumed(BudgetCategory.FUNCTION_CALL)).isEqualTo(2L);
  }

  @Test
  void testCategoryLimitFailsOnTheUnitAfterTheLimit() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build());
    context.record(BudgetCategory.NODE_VISIT);

    assertThatThrownBy(() -> context.record(BudgetCategory.NODE_VISIT))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getCategory()).isEqualTo(BudgetCategory.NODE_VISIT);
              assertThat(exception.isTotalWeightLimit()).isFalse();
              assertThat(exception.getLimit()).isEqualTo(1L);
              assertThat(exception.getConsumedBeforeRequest()).isEqualTo(1L);
              assertThat(exception.getRequestedUnits()).isEqualTo(1L);
              assertThat(exception.getConsumed().get(BudgetCategory.NODE_VISIT)).isEqualTo(1L);
              assertThat(exception.getBudget()).isNotNull();
            });
  }

  @Test
  void testFailedRecordDoesNotConsumeTheUnit() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build());
    context.record(BudgetCategory.NODE_VISIT);

    assertThatThrownBy(() -> context.record(BudgetCategory.NODE_VISIT))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(1L);
  }

  @Test
  void testTotalWeightLimitEnforced() {
    ResourceBudget budget =
        ResourceBudget.builder()
            .weight(BudgetCategory.FUNCTION_CALL, 5L)
            .totalWeightLimit(5L)
            .build();
    EvaluationContext context = new EvaluationContext(budget);
    context.record(BudgetCategory.NODE_VISIT);

    assertThatThrownBy(() -> context.record(BudgetCategory.FUNCTION_CALL))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.isTotalWeightLimit()).isTrue();
              assertThat(exception.getCategory()).isNull();
              assertThat(exception.getLimit()).isEqualTo(5L);
              assertThat(exception.getConsumedBeforeRequest()).isEqualTo(1L);
              assertThat(exception.getRequestedUnits()).isEqualTo(5L);
            });
  }

  @Test
  void testCancellation() {
    EvaluationContext context = unboundedContext();
    assertThat(context.isCancelled()).isFalse();

    context.cancel();

    assertThat(context.isCancelled()).isTrue();
    assertThatThrownBy(() -> context.checkCancelled())
        .isInstanceOf(EvaluationCancelledException.class);
    assertThatThrownBy(() -> context.record(BudgetCategory.NODE_VISIT))
        .isInstanceOf(EvaluationCancelledException.class);
  }

  @Test
  void testCallChainTracksFunctionsAndOperators() {
    EvaluationContext context = unboundedContext();
    Token functionToken = new Token(0, "MAX", TokenType.FUNCTION);
    Token operatorToken = new Token(4, "+", TokenType.INFIX_OPERATOR);

    context.enterFunction(functionToken);
    context.enterOperator(operatorToken);

    List<CallFrame> chain = context.getCallChain();
    assertThat(chain).hasSize(2);
    assertThat(chain.get(0).getName()).isEqualTo("+");
    assertThat(chain.get(0).getKind()).isEqualTo(CallFrame.Kind.OPERATOR);
    assertThat(chain.get(1).getName()).isEqualTo("MAX");
    assertThat(chain.get(1).toString()).isEqualTo("MAX()");

    context.leaveCall();
    assertThat(context.getCallChain()).hasSize(1);
    context.leaveCall();
    assertThat(context.getCallChain()).isEmpty();
  }

  @Test
  void testCallChainSnapshotIsUnmodifiable() {
    EvaluationContext context = unboundedContext();
    context.enterFunction(new Token(0, "F", TokenType.FUNCTION));

    assertThatThrownBy(() -> context.getCallChain().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testExceptionsContainCurrentTokenInformation() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 1L).build());
    Token token = new Token(7, "SUM", TokenType.FUNCTION);
    context.setCurrentToken(token);
    context.enterFunction(token);

    assertThatThrownBy(() -> context.enterFunction(token))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getTokenString()).isEqualTo("SUM");
              assertThat(exception.getStartPosition()).isEqualTo(7);
              assertThat(exception.getEndPosition()).isEqualTo(10);
              assertThat(exception.getCallChain())
                  .extracting(CallFrame::getName)
                  .containsExactly("SUM");
              assertThat(exception.getMessage()).contains("position=7").contains("token='SUM'");
              assertThat(exception.getMessage()).doesNotContain("secret");
            });
  }

  @Test
  void testCancellationExceptionContainsDiagnostics() {
    EvaluationContext context = unboundedContext();
    context.enterFunction(new Token(0, "F", TokenType.FUNCTION));
    context.setCurrentToken(new Token(3, "x", TokenType.VARIABLE_OR_CONSTANT));
    context.cancel();

    assertThatThrownBy(context::checkCancelled)
        .isInstanceOfSatisfying(
            EvaluationCancelledException.class,
            exception -> {
              assertThat(exception.getTokenString()).isEqualTo("x");
              assertThat(exception.getStartPosition()).isEqualTo(3);
              assertThat(exception.getCallChain()).hasSize(1);
              assertThat(exception.getConsumed()).isInstanceOf(Map.class);
            });
  }

  @Test
  void testActivatePublishesContextAndCloseRemovesIt() {
    EvaluationContext context = unboundedContext();

    assertThat(EvaluationContext.getCurrent()).isNull();
    context.activate();
    try {
      assertThat(EvaluationContext.getCurrent()).isSameAs(context);
    } finally {
      context.close();
    }
    assertThat(EvaluationContext.getCurrent()).isNull();
  }

  @Test
  void testStaticCollectionElementHelperIsNullSafeOutsideEvaluation() {
    EvaluationContext.recordCollectionElement();
    EvaluationContext.recordDataAccess();
  }

  @Test
  void testStaticHelpersRecordOnActiveContext() {
    EvaluationContext context = unboundedContext();
    context.activate();
    try {
      EvaluationContext.recordCollectionElement();
      EvaluationContext.recordDataAccess();
    } finally {
      context.close();
    }

    assertThat(context.getConsumed(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(1L);
    assertThat(context.getConsumed(BudgetCategory.DATA_ACCESS)).isEqualTo(1L);
  }

  @Test
  void testLazyEvaluationCountsFirstReadAndCacheHits() throws EvaluationException, ParseException {
    Expression expression = new Expression("a");
    ASTNode node = expression.createExpressionNode("1 + 2");
    EvaluationContext context = unboundedContext();
    context.activate();
    try {
      EvaluationValue first = context.evaluateLazy(expression, node);
      EvaluationValue second = context.evaluateLazy(expression, node);
      EvaluationValue third = context.evaluateLazy(expression, node);

      assertThat(second).isSameAs(first);
      assertThat(third).isSameAs(first);
      assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
      assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isEqualTo(2L);
    } finally {
      context.close();
    }
  }

  @Test
  void testLazyEvaluationVisitsSubtreeOnlyOnce() throws EvaluationException, ParseException {
    Expression expression = new Expression("a");
    ASTNode node = expression.createExpressionNode("1 + 2 + 3");
    EvaluationContext context = unboundedContext();
    context.activate();
    try {
      context.evaluateLazy(expression, node);
      long visitsAfterFirst = context.getConsumed(BudgetCategory.NODE_VISIT);
      context.evaluateLazy(expression, node);

      assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(visitsAfterFirst);
    } finally {
      context.close();
    }
  }

  @Test
  void testLazyCacheHitLimitStopsRepeatedReads() throws EvaluationException, ParseException {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.LAZY_CACHE_HIT, 1L).build());
    Expression expression = new Expression("a");
    ASTNode node = expression.createExpressionNode("1");
    context.activate();
    try {
      context.evaluateLazy(expression, node);
      context.evaluateLazy(expression, node);

      assertThatThrownBy(() -> context.evaluateLazy(expression, node))
          .isInstanceOfSatisfying(
              BudgetExceededException.class,
              exception ->
                  assertThat(exception.getCategory()).isEqualTo(BudgetCategory.LAZY_CACHE_HIT));
    } finally {
      context.close();
    }
  }

  @Test
  void testCloseRemovesNonTopContextFromStack() {
    EvaluationContext outer = unboundedContext();
    EvaluationContext inner = unboundedContext();
    outer.activate();
    inner.activate();

    outer.close();

    assertThat(EvaluationContext.getCurrent()).isSameAs(inner);
    inner.close();
    assertThat(EvaluationContext.getCurrent()).isNull();
  }

  @Test
  void testLargeWeightsAreSaturatedInsteadOfOverflowing() {
    ResourceBudget budget =
        ResourceBudget.builder()
            .weight(BudgetCategory.NODE_VISIT, Long.MAX_VALUE)
            .totalWeightLimit(10L)
            .build();
    EvaluationContext context = new EvaluationContext(budget);

    assertThatThrownBy(() -> context.record(BudgetCategory.NODE_VISIT, 2))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> assertThat(exception.isTotalWeightLimit()).isTrue());
  }

  @Test
  void testOperatorFrameToString() {
    assertThat(new CallFrame(CallFrame.Kind.OPERATOR, "+").toString()).isEqualTo("+");
  }

  @Test
  void testContextExposesBudget() {
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build();
    EvaluationContext context = new EvaluationContext(budget);

    assertThat(context.getBudget()).isSameAs(budget);
  }
}
