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
import com.ezylang.evalex.EvaluationCancelledException;
import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameter;
import com.ezylang.evalex.parser.Token;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvaluationCancellationTest {

  /** Custom function that blocks until the active evaluation is cancelled. */
  @FunctionParameter(name = "input")
  public static class WaitForCancelFunction extends AbstractFunction {

    static final CountDownLatch entered = new CountDownLatch(1);

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token functionToken, EvaluationValue... parameterValues)
        throws EvaluationException {
      EvaluationContext context = EvaluationContext.current();
      entered.countDown();
      while (!context.isCancelled()) {
        try {
          TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      context.checkCancelled(functionToken);
      return expression.convertValue(0);
    }
  }

  private Expression waitExpression() {
    ExpressionConfiguration configuration =
        ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(java.util.Map.entry("WAIT", new WaitForCancelFunction()));
    return new Expression("WAIT(1)", configuration);
  }

  @Test
  void cancellationStopsLongRunningCustomFunction() throws Exception {
    EvaluationContext context = EvaluationContext.cancellable();
    Expression expression = waitExpression();
    AtomicReference<Throwable> thrown = new AtomicReference<>();

    Thread evaluation =
        new Thread(
            () -> {
              try {
                expression.evaluate(context);
              } catch (EvaluationException | com.ezylang.evalex.parser.ParseException e) {
                thrown.set(e);
              }
            });
    evaluation.setDaemon(true);
    evaluation.start();

    assertThat(WaitForCancelFunction.entered.await(5, TimeUnit.SECONDS)).isTrue();
    context.cancel("requested by test");
    evaluation.join(5_000);

    assertThat(evaluation.isAlive()).isFalse();
    assertThat(thrown.get())
        .isInstanceOf(EvaluationCancelledException.class)
        .hasMessageContaining("requested by test");
  }

  @Test
  void cancellationBeforeEvaluationIsObservedImmediately() {
    EvaluationContext context = EvaluationContext.cancellable();
    context.cancel("pre-cancelled");

    assertThatThrownBy(() -> new Expression("1 + 2").evaluate(context))
        .isInstanceOf(EvaluationCancelledException.class)
        .hasMessageContaining("pre-cancelled")
        .isInstanceOf(EvaluationException.class);
  }

  @Test
  void cancellationIsDifferentFromBudgetExceeded() {
    EvaluationContext context = EvaluationContext.cancellable();
    context.cancel("stop");

    assertThatThrownBy(() -> new Expression("1").evaluate(context))
        .isInstanceOf(EvaluationCancelledException.class)
        .isNotInstanceOf(EvaluationBudgetException.class);
  }
}
