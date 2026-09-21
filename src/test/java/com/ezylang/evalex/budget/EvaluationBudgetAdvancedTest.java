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
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.DataAccessorIfc;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.data.MapBasedDataAccessor;
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameterDefinition;
import com.ezylang.evalex.operators.AbstractOperator;
import com.ezylang.evalex.operators.InfixOperator;
import com.ezylang.evalex.operators.OperatorIfc;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvaluationBudgetAdvancedTest {

  @Test
  void testNestedExpressionNodeJoinsParentBudget() throws EvaluationException, ParseException {
    Expression outer = new Expression("child + 1");
    outer.with(
        "child", EvaluationValue.expressionNodeValue(outer.createExpressionNode("1 + 2 + 3")));
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 4L).build());

    assertThatThrownBy(() -> outer.evaluate(context))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> assertThat(exception.getCategory()).isEqualTo(BudgetCategory.NODE_VISIT));
  }

  @Test
  void testNestedExpressionNodeSharesParentContext() throws EvaluationException, ParseException {
    Expression outer = new Expression("child + 10");
    outer.with("child", EvaluationValue.expressionNodeValue(outer.createExpressionNode("1 + 2")));
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThat(outer.evaluate(context).getNumberValue()).isEqualByComparingTo("13");
    // child subtree: 3 nodes, outer: variable + number + addition = 3 nodes
    assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(6L);
  }

  @Test
  void testCustomFunctionEvaluatingNewExpressionJoinsParentBudget() {
    AbstractFunction nested =
        new AbstractFunction() {
          @Override
          public EvaluationValue evaluate(
              Expression expression, Token functionToken, EvaluationValue... parameterValues)
              throws EvaluationException {
            try {
              return new Expression("1 + 2 + 3 + 4").evaluate();
            } catch (ParseException e) {
              throw new EvaluationException(functionToken, e.getMessage());
            }
          }

          @Override
          public List<FunctionParameterDefinition> getFunctionParameterDefinitions() {
            return List.of();
          }
        };
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("NESTED", nested));
    Expression expression = new Expression("NESTED()", configuration);
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 5L).build());

    assertThatThrownBy(() -> expression.evaluate(context))
        .isInstanceOf(BudgetExceededException.class);
  }

  @Test
  void testInfiniteRecursiveCustomFunctionIsStoppedByBudget()
      throws EvaluationException, ParseException {
    AbstractFunction loop =
        new AbstractFunction() {
          @Override
          public EvaluationValue evaluate(
              Expression expression, Token functionToken, EvaluationValue... parameterValues) {
            try {
              return expression.evaluateSubtree(expression.createExpressionNode("LOOP()"));
            } catch (EvaluationException | ParseException e) {
              throw new IllegalStateException(e);
            }
          }

          @Override
          public List<FunctionParameterDefinition> getFunctionParameterDefinitions() {
            return List.of();
          }
        };
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("LOOP", loop));
    Expression expression = new Expression("LOOP()", configuration);
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 50L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOfSatisfying(
            BudgetExceededException.class,
            exception -> {
              assertThat(exception.getCategory()).isEqualTo(BudgetCategory.FUNCTION_CALL);
              assertThat(exception.getConsumedBeforeRequest()).isEqualTo(50L);
              assertThat(exception.getCallChain()).hasSizeGreaterThan(10);
              assertThat(exception.getCallChain())
                  .allSatisfy(frame -> assertThat(frame.getName()).isEqualTo("LOOP"));
            });
  }

  @Test
  void testThrowingAccessorPropagatesOriginalException() {
    DataAccessorIfc failingAccessor =
        new DataAccessorIfc() {
          @Override
          public EvaluationValue getData(String variable) {
            throw new IllegalStateException("accessor boom");
          }

          @Override
          public void setData(String variable, EvaluationValue value) {}
        };
    Expression expression =
        new Expression(
            "x",
            ExpressionConfiguration.defaultConfiguration().toBuilder()
                .dataAccessorSupplier(() -> failingAccessor)
                .build());

    assertThatThrownBy(expression::evaluate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("accessor boom");
  }

  @Test
  void testThrowingAccessorStillCountedBeforeFailure() {
    DataAccessorIfc failingAccessor =
        new DataAccessorIfc() {
          @Override
          public EvaluationValue getData(String variable) {
            throw new IllegalStateException("accessor boom");
          }

          @Override
          public void setData(String variable, EvaluationValue value) {}
        };
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration().toBuilder()
            .dataAccessorSupplier(() -> failingAccessor)
            .build();
    Expression expression = new Expression("x", configuration);
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThatThrownBy(() -> expression.evaluate(context))
        .isInstanceOf(IllegalStateException.class);
    assertThat(context.getConsumed(BudgetCategory.DATA_ACCESS)).isEqualTo(1L);
  }

  @InfixOperator(precedence = OperatorIfc.OPERATOR_PRECEDENCE_ADDITIVE, operandsLazy = true)
  static class RepeatingLazyOperator extends AbstractOperator {
    @Override
    public EvaluationValue evaluate(
        Expression expression, Token operatorToken, EvaluationValue... operands)
        throws EvaluationException {
      expression.evaluateLazyParameter(operands[0].getExpressionNode());
      EvaluationValue cached = expression.evaluateLazyParameter(operands[0].getExpressionNode());
      return cached;
    }
  }

  @Test
  void testCustomOperatorRepeatedLazyReadCountsCacheHit()
      throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalOperators(Map.entry("=>", new RepeatingLazyOperator()));
    Expression expression = new Expression("1 => 2", configuration);
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThat(expression.evaluate(context).getNumberValue()).isEqualByComparingTo("1");
    assertThat(context.getConsumed(BudgetCategory.LAZY_EVALUATION)).isEqualTo(1L);
    assertThat(context.getConsumed(BudgetCategory.LAZY_CACHE_HIT)).isEqualTo(1L);
  }

  @com.ezylang.evalex.functions.FunctionParameter(name = "values", isVarArg = true)
  static class CountFunction extends AbstractFunction {
    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues) {
      for (EvaluationValue value : parameterValues) {
        for (EvaluationValue ignored : value.getArrayValue()) {
          EvaluationContext.recordCollectionElement();
        }
      }
      return expression.convertValue(parameterValues[0].getArrayValue().size());
    }
  }

  @Test
  void testCustomFunctionCanRecordCollectionElements() throws EvaluationException, ParseException {
    AbstractFunction count = new CountFunction();
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("COUNT", count));
    Expression expression = new Expression("COUNT(a)", configuration);
    expression.with("a", List.of(1, 2, 3));
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);

    assertThat(expression.evaluate(context).getNumberValue()).isEqualByComparingTo("3");
    assertThat(context.getConsumed(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void testCancellationStopsLongRunningEvaluation()
      throws Exception, EvaluationException, ParseException {
    AbstractFunction blocking =
        new AbstractFunction() {
          @Override
          public EvaluationValue evaluate(
              Expression expression, Token functionToken, EvaluationValue... parameterValues) {
            try {
              return expression.evaluateSubtree(expression.createExpressionNode("WAIT()"));
            } catch (EvaluationException | ParseException e) {
              throw new IllegalStateException(e);
            }
          }

          @Override
          public List<FunctionParameterDefinition> getFunctionParameterDefinitions() {
            return List.of();
          }
        };
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("WAIT", blocking));
    Expression expression = new Expression("WAIT()", configuration);
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 1_000_000L).build());
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> future =
        executor.submit(
            () -> {
              started.countDown();
              try {
                expression.evaluate(context);
              } catch (EvaluationCancelledException e) {
                throw e;
              } catch (EvaluationException | ParseException e) {
                throw new IllegalStateException(e);
              }
            });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.yield();
    context.cancel();

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(EvaluationCancelledException.class);
    executor.shutdownNow();
  }

  @Test
  void testCancelledExceptionIsNotEvaluationException() throws Exception {
    Expression expression = new Expression("a + b");
    MapBasedDataAccessor accessor = new MapBasedDataAccessor();
    accessor.setData("a", EvaluationValue.numberValue(BigDecimal.ONE));
    EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);
    context.cancel();

    assertThatThrownBy(() -> expression.evaluate(context))
        .isInstanceOf(EvaluationCancelledException.class)
        .isNotInstanceOf(EvaluationException.class);
  }

  @Test
  void testBudgetExceededIsNotOrdinaryEvaluationException() {
    Expression expression = new Expression("1 + 2 + 3");
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOf(BudgetExceededException.class)
        .isNotInstanceOf(EvaluationException.class)
        .isInstanceOf(BudgetControlException.class);
  }

  @Test
  void testParallelEvaluationsOfSameExpressionHaveIndependentBudgets() throws Exception {
    Expression expression = new Expression("x + x + x + x + x");
    int threads = 32;
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    AtomicInteger cancelled = new AtomicInteger();
    List<Future<?>> futures = new java.util.ArrayList<>();

    for (int i = 0; i < threads; i++) {
      final int value = i;
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                  Expression copy = expression.copy();
                  copy.with("x", value);
                  EvaluationContext context = new EvaluationContext(ResourceBudget.UNBOUNDED);
                  assertThat(copy.evaluate(context).getNumberValue())
                      .isEqualByComparingTo(String.valueOf(value * 5));
                  assertThat(context.getConsumed(BudgetCategory.NODE_VISIT)).isEqualTo(9L);
                } catch (EvaluationCancelledException e) {
                  cancelled.incrementAndGet();
                } catch (Throwable t) {
                  failures.add(t);
                }
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(failures).isEmpty();
    assertThat(cancelled).hasValue(0);
  }

  @Test
  void testThreadLocalIsClearedAfterThrowingEvaluation() {
    Expression expression = new Expression("1 + 2 + 3");
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 1L).build();

    assertThatThrownBy(() -> expression.evaluate(budget))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(EvaluationContext.getCurrent()).isNull();
  }
}
