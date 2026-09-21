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
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ParseException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinCollectionBudgetTest {

  private static EvaluationContext contextWithTotal(long limit) {
    return new EvaluationContext(ResourceBudget.builder().totalWeightLimit(limit).build());
  }

  @Test
  void coalesceScalarParametersAreNotCountedAsCollectionElements()
      throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    String result =
        new Expression("COALESCE(a, b)")
            .with("a", 1)
            .and("b", 2)
            .evaluate(context)
            .getStringValue();

    assertThat(result).isEqualTo("1");
    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isZero();
  }

  @Test
  void coalesceArrayElementsAreCountedUntilFirstNonNull()
      throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    new Expression("COALESCE(values)")
        .with("values", List.of(EvaluationValue.NULL_VALUE, EvaluationValue.NULL_VALUE, 5, 9))
        .evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void coalesceNullArrayCountsAllLeafElements() throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    new Expression("COALESCE(values)")
        .with(
            "values",
            List.of(
                EvaluationValue.NULL_VALUE, EvaluationValue.NULL_VALUE, EvaluationValue.NULL_VALUE))
        .evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void averageCountsLeafElements() throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    new Expression("AVERAGE(values)").with("values", List.of(1, 2, 3, 4)).evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(4L);
  }

  @Test
  void minCountsLeafElements() throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    new Expression("MIN(values)").with("values", List.of(3, 1, 2)).evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void maxCountsLeafElements() throws EvaluationException, ParseException {
    EvaluationContext context = contextWithTotal(1_000L);

    new Expression("MAX(values)").with("values", List.of(3, 1, 2)).evaluate(context);

    assertThat(context.getConsumedCount(BudgetCategory.COLLECTION_ELEMENT)).isEqualTo(3L);
  }

  @Test
  void averageElementLimitCanBeExceeded() {
    EvaluationContext context =
        new EvaluationContext(
            ResourceBudget.builder().categoryLimit(BudgetCategory.COLLECTION_ELEMENT, 1L).build());

    assertThatThrownBy(
            () ->
                new Expression("AVERAGE(values)")
                    .with("values", List.of(1, 2, 3))
                    .evaluate(context))
        .isInstanceOf(EvaluationBudgetException.class)
        .satisfies(
            error ->
                assertThat(((EvaluationBudgetException) error).getExceededCategory())
                    .isEqualTo(BudgetCategory.COLLECTION_ELEMENT));
  }
}
