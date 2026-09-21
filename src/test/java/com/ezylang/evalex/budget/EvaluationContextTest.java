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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ezylang.evalex.EvaluationBudgetException;
import com.ezylang.evalex.EvaluationCancelledException;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ASTNode;
import com.ezylang.evalex.parser.Token;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvaluationContextTest {

  private static final Token TOKEN = new Token(3, "SUM", Token.TokenType.FUNCTION);

  @Test
  void currentOutsideEvaluationReturnsInactiveNoOpContext() throws Exception {
    EvaluationContext context = EvaluationContext.current();

    assertThat(context.isActive()).isFalse();
  }

  @Test
  void noOpContextAcceptsChargesAndCancellationWithoutEffect() throws Exception {
    EvaluationContext context = EvaluationContext.current();

    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
    context.charge(BudgetCategory.FUNCTION_CALL, 5L, TOKEN);
    context.enterNode(TOKEN);
    context.checkCancelled(TOKEN);

    assertThat(context.getConsumedTotalWeight()).isZero();
    assertThat(context.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isZero();
    assertThat(context.consumedByCategorySnapshot()).hasSize(BudgetCategory.values().length);
  }

  @Test
  void nullBudgetIsRejected() throws Exception {
    assertThatIllegalArgumentException().isThrownBy(() -> new EvaluationContext(null));
  }

  @Test
  void chargeCountsTotalWeightAndCategory() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
    context.charge(BudgetCategory.FUNCTION_CALL, 4L, TOKEN);

    assertThat(context.getConsumedTotalWeight()).isEqualTo(5L);
    assertThat(context.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isEqualTo(1L);
    assertThat(context.getConsumedCount(BudgetCategory.FUNCTION_CALL)).isEqualTo(4L);
    assertThat(context.consumedByCategorySnapshot())
        .containsEntry(BudgetCategory.AST_NODE_VISIT, 1L)
        .containsEntry(BudgetCategory.FUNCTION_CALL, 4L);
  }

  @Test
  void exactLimitIsAcceptedAndNextEventExceeds() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(2L).build());

    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);

    assertThatThrownBy(() -> context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN))
        .isInstanceOf(EvaluationBudgetException.class)
        .hasMessageContaining("total weight limit");
  }

  @Test
  void categoryLimitIsEnforcedIndependently() throws Exception {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, 1L).build());

    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
    context.charge(BudgetCategory.FUNCTION_CALL, TOKEN);

    assertThatThrownBy(() -> context.charge(BudgetCategory.FUNCTION_CALL, TOKEN))
        .isInstanceOf(EvaluationBudgetException.class)
        .hasMessageContaining("function call");
  }

  @Test
  void budgetExceptionCarriesDiagnosticsWithoutDataValues() throws Exception {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder()
                .totalWeightLimit(3L)
                .categoryLimit(BudgetCategory.AST_NODE_VISIT, 2L)
                .build());
    context.pushCallFrame("function OUTER");
    context.pushCallFrame("operator +");

    Throwable thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            EvaluationBudgetException.class,
            () -> {
              context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
              context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
              context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
            });
    EvaluationBudgetException exception = (EvaluationBudgetException) thrown;

    assertThat(exception.getExceededCategory()).isEqualTo(BudgetCategory.AST_NODE_VISIT);
    assertThat(exception.getStartPosition()).isEqualTo(3);
    assertThat(exception.getTokenString()).isEqualTo("SUM");
    assertThat(exception.getCallChain()).containsExactly("function OUTER", "operator +");
    assertThat(exception.getCategoryLimits()).containsEntry(BudgetCategory.AST_NODE_VISIT, 2L);
    assertThat(exception.getConsumedByCategory()).containsEntry(BudgetCategory.AST_NODE_VISIT, 3L);
    assertThat(exception.getMessage()).doesNotContain("password", "secret");
  }

  @Test
  void callFramesCanBePushedAndPopped() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());

    context.pushCallFrame("function A");
    context.pushCallFrame("function B");
    assertThat(context.callChainSnapshot()).containsExactly("function A", "function B");

    context.popCallFrame("function B");
    assertThat(context.callChainSnapshot()).containsExactly("function A");

    context.popCallFrame("function A");
    assertThat(context.callChainSnapshot()).isEmpty();
  }

  @Test
  void cancellationIsObservedOnCheckAndCharge() throws Exception {
    EvaluationContext context = EvaluationContext.cancellable();

    assertThat(context.isCancelled()).isFalse();
    context.cancel("stop from another thread");

    assertThat(context.getCancellationReason()).isEqualTo("stop from another thread");
    assertThatThrownBy(() -> context.checkCancelled(TOKEN))
        .isInstanceOf(EvaluationCancelledException.class)
        .hasMessageContaining("stop from another thread");
    assertThatThrownBy(() -> context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN))
        .isInstanceOf(EvaluationCancelledException.class);
  }

  @Test
  void cancellationExceptionCarriesPositionAndCallChain() throws Exception {
    EvaluationContext context = EvaluationContext.cancellable();
    context.pushCallFrame("function LOOP");
    context.charge(BudgetCategory.AST_NODE_VISIT, TOKEN);
    context.cancel("done");

    Throwable thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            EvaluationCancelledException.class, () -> context.checkCancelled(TOKEN));
    EvaluationCancelledException exception = (EvaluationCancelledException) thrown;

    assertThat(exception.getCancellationReason()).isEqualTo("done");
    assertThat(exception.getTokenString()).isEqualTo("SUM");
    assertThat(exception.getCallChain()).containsExactly("function LOOP");
    assertThat(exception.getConsumedTotalWeight()).isEqualTo(1L);
  }

  @Test
  void cancellableContextHasUnlimitedBudgetAndNoLazyCache() throws Exception {
    EvaluationContext context = EvaluationContext.cancellable();
    ASTNode node = new ASTNode(new Token(1, "a", Token.TokenType.VARIABLE_OR_CONSTANT));
    AtomicInteger evaluations = new AtomicInteger();

    context.evaluateLazyNode(
        node,
        unused -> {
          evaluations.incrementAndGet();
          return EvaluationValue.TRUE;
        },
        TOKEN);
    context.evaluateLazyNode(
        node,
        unused -> {
          evaluations.incrementAndGet();
          return EvaluationValue.TRUE;
        },
        TOKEN);

    assertThat(evaluations).hasValue(2);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_EVALUATION)).isEqualTo(2L);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_CACHE_HIT)).isZero();
  }

  @Test
  void limitedContextCachesLazyNodesAndCountsHits() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());
    ASTNode node = new ASTNode(new Token(1, "a", Token.TokenType.VARIABLE_OR_CONSTANT));
    AtomicInteger evaluations = new AtomicInteger();

    EvaluationValue first =
        context.evaluateLazyNode(
            node,
            unused -> {
              evaluations.incrementAndGet();
              return EvaluationValue.TRUE;
            },
            TOKEN);
    EvaluationValue second = context.evaluateLazyNode(node, unused -> EvaluationValue.FALSE, TOKEN);

    assertThat(first).isSameAs(second);
    assertThat(evaluations).hasValue(1);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
    assertThat(context.getConsumedCount(BudgetCategory.LAZY_CACHE_HIT)).isEqualTo(1L);
  }

  @Test
  void negativeWeightIsRejected() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> context.charge(BudgetCategory.AST_NODE_VISIT, -1L, TOKEN));
  }

  @Test
  void activationStackExposesAndRestoresContexts() throws Exception {
    EvaluationContext outer =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());
    EvaluationContext inner =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(20L).build());

    outer.activate();
    try {
      assertThat(EvaluationContext.current()).isSameAs(outer);
      inner.activate();
      assertThat(EvaluationContext.current()).isSameAs(inner);
      inner.deactivate();
      assertThat(EvaluationContext.current()).isSameAs(outer);
    } finally {
      outer.deactivate();
    }

    assertThat(EvaluationContext.current().isActive()).isFalse();
  }

  @Test
  void largeWeightsSaturateInsteadOfOverflowing() throws Exception {
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());

    assertThatThrownBy(() -> context.charge(BudgetCategory.AST_NODE_VISIT, Long.MAX_VALUE, TOKEN))
        .isInstanceOf(EvaluationBudgetException.class);

    assertThat(context.getConsumedTotalWeight()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void getBudgetReturnsConfiguredBudget() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(7L).build();
    EvaluationContext context = new EvaluationContext(budget);

    assertThat(context.getBudget()).isSameAs(budget);
  }

  @Test
  void deactivateRemovesContextEvenWhenNotTopOfStack() {
    EvaluationContext outer =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());
    EvaluationContext middle =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());
    EvaluationContext inner =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(10L).build());

    outer.activate();
    middle.activate();
    inner.activate();
    middle.deactivate();

    assertThat(EvaluationContext.current()).isSameAs(inner);
    inner.deactivate();
    outer.deactivate();
    assertThat(EvaluationContext.current().isActive()).isFalse();
  }

  @Test
  void chargeAfterLargeValueSaturatesOnEveryFurtherCharge() throws Exception {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().totalWeightLimit(Long.MAX_VALUE - 1L).build());
    context.charge(BudgetCategory.AST_NODE_VISIT, 1L, TOKEN);

    // Adding a weight that would overflow a long must saturate and exceed the limit.
    org.junit.jupiter.api.Assertions.assertThrows(
        EvaluationBudgetException.class,
        () -> context.charge(BudgetCategory.AST_NODE_VISIT, Long.MAX_VALUE, TOKEN));

    assertThat(context.getConsumedTotalWeight()).isEqualTo(Long.MAX_VALUE);
  }
}
