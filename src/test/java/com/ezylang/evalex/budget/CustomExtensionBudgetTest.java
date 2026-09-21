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
import com.ezylang.evalex.data.DataAccessorIfc;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameter;
import com.ezylang.evalex.operators.AbstractOperator;
import com.ezylang.evalex.operators.InfixOperator;
import com.ezylang.evalex.operators.OperatorIfc;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomExtensionBudgetTest {

  /** Custom function that accounts ten internal units of work per invocation. */
  @FunctionParameter(name = "input")
  public static class HeavyFunction extends AbstractFunction {

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues)
        throws EvaluationException {
      EvaluationContext.current().charge(BudgetCategory.FUNCTION_CALL, 10L, functionToken);
      return expression.convertValue(42);
    }
  }

  /** Custom infix operator that accounts one collection element per operand array entry. */
  @InfixOperator(precedence = OperatorIfc.OPERATOR_PRECEDENCE_ADDITIVE)
  public static class CountingOperator extends AbstractOperator {

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token operatorToken, EvaluationValue... operands)
        throws EvaluationException {
      for (EvaluationValue element : operands[0].getArrayValue()) {
        EvaluationContext.current().charge(BudgetCategory.COLLECTION_ELEMENT, operatorToken);
      }
      return expression.convertValue(operands[0].getArrayValue().size());
    }
  }

  /** Accessor that charges its read before returning a value. */
  public static class ChargingAccessor implements DataAccessorIfc {

    @Override
    public EvaluationValue getData(String variable) {
      try {
        EvaluationContext.current().charge(BudgetCategory.DATA_ACCESS, null);
      } catch (EvaluationException e) {
        throw new IllegalStateException(e);
      }
      return EvaluationValue.numberValue(BigDecimal.valueOf(7));
    }

    @Override
    public void setData(String variable, EvaluationValue value) {
      // not used by the test
    }
  }

  /** Accessor that always fails with a runtime exception. */
  public static class FailingAccessor implements DataAccessorIfc {

    @Override
    public EvaluationValue getData(String variable) {
      throw new IllegalStateException("accessor exploded for variable " + variable);
    }

    @Override
    public void setData(String variable, EvaluationValue value) {
      // not used by the test
    }
  }

  @Test
  void customFunctionCanChargeAdditionalWeight() {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(Map.entry("HEAVY", new HeavyFunction()));
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(5L).build());

    assertThatThrownBy(() -> new Expression("HEAVY(1)", configuration).evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error -> {
              EvaluationBudgetException budgetException = (EvaluationBudgetException) error;
              // One function call from the evaluator plus ten units from the function itself.
              assertThat(budgetException.getConsumedTotalWeight()).isGreaterThanOrEqualTo(11L);
              assertThat(budgetException.getCallChain()).contains("function HEAVY");
            });
  }

  @Test
  void customOperatorCanChargeCollectionElements() {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalOperators(Map.entry("~", new CountingOperator()));
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.COLLECTION_ELEMENT, 1L).build());

    assertThatThrownBy(
            () ->
                new Expression("values ~ 0", configuration)
                    .with("values", List.of(1, 2, 3))
                    .evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.COLLECTION_ELEMENT));
  }

  @Test
  void customAccessorChargesAreVisibleInContext() throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.builder().dataAccessorSupplier(ChargingAccessor::new).build();
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    new Expression("x + x", configuration).evaluate(context);

    // The built-in evaluator and the accessor each charge one read per variable lookup.
    assertThat(context.getConsumedCount(BudgetCategory.DATA_ACCESS)).isEqualTo(4L);
  }

  @Test
  void failingAccessorPropagatesRuntimeExceptionAndCleansUpContext()
      throws EvaluationException, ParseException {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.builder().dataAccessorSupplier(FailingAccessor::new).build();
    EvaluationContext context =
        new EvaluationContext(ResourceBudget.builder().totalWeightLimit(100L).build());

    assertThatThrownBy(() -> new Expression("missing", configuration).evaluate(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accessor exploded");

    assertThat(EvaluationContext.current().isActive()).isFalse();
  }
}
