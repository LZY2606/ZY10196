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
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NestedExpressionAndThreadingBudgetTest {

  /** Custom function that evaluates a completely separate Expression instance. */
  @FunctionParameter(name = "value")
  public static class NestedEvaluateFunction extends AbstractFunction {

    static final Expression INNER_EXPRESSION = new Expression("1 + 2 + 3 + 4 + 5");

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues)
        throws EvaluationException {
      try {
        return INNER_EXPRESSION.evaluate();
      } catch (ParseException e) {
        throw new EvaluationException(functionToken, e.getMessage());
      }
    }
  }

  /** Function that decrements a shared counter variable and recurses while it is positive. */
  @FunctionParameter(name = "n", isLazy = true)
  public static class RecursiveFunction extends AbstractFunction {

    private final ExpressionConfiguration configuration;

    RecursiveFunction() {
      this.configuration =
          ExpressionConfiguration.defaultConfiguration()
              .withAdditionalFunctions(Map.entry("RECURSIVE", this));
    }

    Expression expressionWithStart(int start) {
      return new Expression("RECURSIVE(n)", configuration).with("n", start);
    }

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues)
        throws EvaluationException {
      int current = expression.getDataAccessor().getData("n").getNumberValue().intValue();
      if (current <= 0) {
        return expression.convertValue(0);
      }
      try {
        return new Expression("RECURSIVE(n - 1)", configuration).with("n", current - 1).evaluate();
      } catch (ParseException e) {
        throw new EvaluationException(functionToken, e.getMessage());
      }
    }
  }

  @Test
  void nestedExpressionReusesParentBudgetAndCanNotBypassIt() {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("NESTED", new NestedEvaluateFunction()));
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(5L).build());

    assertThatThrownBy(() -> new Expression("NESTED(1)", configuration).evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getCallChain()).contains("function NESTED");
              assertThat(budgetException.getConsumedTotalWeight()).isGreaterThan(5L);
            });
  }

  @Test
  void infiniteRecursionThroughCustomFunctionsIsStoppedByBudget() {
    RecursiveFunction function = new RecursiveFunction();
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, 50L).build());

    assertThatThrownBy(() -> function.expressionWithStart(Integer.MAX_VALUE).evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              assertThat(budgetException.getExceededCategory())
                  .isEqualTo(BudgetCategory.FUNCTION_CALL);
              assertThat(budgetException.getCallChain()).contains("function RECURSIVE");
            });
  }

  @Test
  void sameCompiledExpressionCanBeEvaluatedInParallelWithSeparateContexts() throws Exception {
    Expression shared = new Expression("a + b").with("a", 0).and("b", 0);
    shared.validate();
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    for (int i = 0; i < threadCount; i++) {
      int value = i;
      executor.submit(
          () -> {
            try {
              start.await();
              Expression copy = shared.copy();
              copy.with("a", value).and("b", 1);
              EvaluationContext context =
                  new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());
              EvaluationValue result = copy.evaluate(context);
              if (result.getNumberValue().intValue() != value + 1) {
                failures.add(new AssertionError("unexpected result " + result));
              }
            } catch (Throwable t) {
              failures.add(t);
            }
          });
    }

    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    assertThat(failures).isEmpty();
  }

  @Test
  void nestedExpressionUnderLargeParentBudgetSucceedsAndSharesConsumption()
      throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("NESTED", new NestedEvaluateFunction()));
    EvaluationContext outer =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(1_000L).build());

    EvaluationValue result = new Expression("NESTED(1)", configuration).evaluate(outer);

    assertThat(result.getNumberValue().intValue()).isEqualTo(15);
    // The inner expression visits its five number literals and four operators against the parent
    // budget, proving its work was not accounted against a fresh independent budget.
    assertThat(outer.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isGreaterThanOrEqualTo(11L);
    assertThat(outer.getConsumedCount(BudgetCategory.OPERATOR_CALL)).isGreaterThanOrEqualTo(4L);
  }

  @Test
  void nestedNoArgEvaluateAlsoReusesActiveParentContext() throws Exception {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("NESTED", new NestedEvaluateFunction()));
    EvaluationContext outer =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(1_000L).build());

    EvaluationValue result = new Expression("NESTED(1)", configuration).evaluate(outer);

    assertThat(result.getNumberValue().intValue()).isEqualTo(15);
    assertThat(outer.getConsumedCount(BudgetCategory.AST_NODE_VISIT)).isGreaterThanOrEqualTo(11L);
  }
}
