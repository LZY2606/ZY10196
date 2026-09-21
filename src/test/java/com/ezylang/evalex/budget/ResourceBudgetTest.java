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

import com.ezylang.evalex.EvaluationBudgetException;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class ResourceBudgetTest {

  @Test
  void defaultBudgetIsUnlimited() {
    ResourceBudget budget = ResourceBudget.builder().build();

    assertThat(budget.isUnlimited()).isTrue();
    assertThat(budget.getTotalWeightLimit()).isEqualTo(ResourceBudget.UNLIMITED);
    assertThat(budget.getCategoryLimit(BudgetCategory.AST_NODE_VISIT))
        .isEqualTo(ResourceBudget.UNLIMITED);
    assertThat(budget.getCategoryLimits()).isEmpty();
  }

  @Test
  void totalWeightLimitMakesBudgetLimited() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(10L).build();

    assertThat(budget.isUnlimited()).isFalse();
    assertThat(budget.getTotalWeightLimit()).isEqualTo(10L);
  }

  @Test
  void categoryLimitMakesBudgetLimitedAndIsReturned() {
    ResourceBudget budget =
        ResourceBudget.builder()
            .categoryLimit(BudgetCategory.FUNCTION_CALL, 2L)
            .categoryLimit(BudgetCategory.OPERATOR_CALL, 5L)
            .build();

    assertThat(budget.isUnlimited()).isFalse();
    assertThat(budget.getCategoryLimit(BudgetCategory.FUNCTION_CALL)).isEqualTo(2L);
    assertThat(budget.getCategoryLimit(BudgetCategory.OPERATOR_CALL)).isEqualTo(5L);
    assertThat(budget.getCategoryLimit(BudgetCategory.DATA_ACCESS))
        .isEqualTo(ResourceBudget.UNLIMITED);
  }

  @Test
  void categoryLimitMapIsImmutable() {
    ResourceBudget budget =
        ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, 2L).build();

    assertThat(budget.getCategoryLimits()).hasSize(1);
  }

  @Test
  void negativeTotalLimitIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ResourceBudget.builder().totalWeightLimit(-2L).build())
        .withMessageContaining("totalWeightLimit");
  }

  @Test
  void zeroLimitsAreAllowed() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(0L).build();

    assertThat(budget.getTotalWeightLimit()).isZero();
  }

  @Test
  void negativeCategoryLimitIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> ResourceBudget.builder().categoryLimit(BudgetCategory.FUNCTION_CALL, -5L).build())
        .withMessageContaining("FUNCTION_CALL");
  }

  @Test
  void unlimitedConstantIsSharedAndUnlimited() {
    assertThat(ResourceBudget.UNLIMITED_BUDGET.isUnlimited()).isTrue();
  }

  @Test
  void nullCategoryEntryIsRejected() {
    HashMap<BudgetCategory, Long> limits = new HashMap<>();
    limits.put(null, 1L);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ResourceBudget(ResourceBudget.UNLIMITED, limits));
  }

  @Test
  void budgetCategoryDescriptionsAreReadable() {
    assertThat(BudgetCategory.AST_NODE_VISIT.getDescription()).isEqualTo("AST node visit");
    assertThat(BudgetCategory.DATA_ACCESS.getDescription()).contains("data accessor");
  }

  @Test
  void nullTokenBudgetExceptionUsesNeutralPosition() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(0L).build();
    EvaluationContext context = new EvaluationContext(budget);

    EvaluationBudgetException exception =
        org.junit.jupiter.api.Assertions.assertThrows(
            EvaluationBudgetException.class,
            () -> context.charge(BudgetCategory.AST_NODE_VISIT, null));

    assertThat(exception.getStartPosition()).isZero();
    assertThat(exception.getEndPosition()).isZero();
    assertThat(exception.getTokenString()).isEmpty();
  }

  @Test
  void builderAcceptsUnlimitedExplicitlyPerCategory() {
    ResourceBudget budget =
        ResourceBudget.builder()
            .categoryLimit(BudgetCategory.AST_NODE_VISIT, ResourceBudget.UNLIMITED)
            .totalWeightLimit(5L)
            .build();

    assertThat(budget.getCategoryLimit(BudgetCategory.AST_NODE_VISIT))
        .isEqualTo(ResourceBudget.UNLIMITED);
    assertThat(budget.isUnlimited()).isFalse();
  }

  @Test
  void constructorAcceptsNullAsUnlimitedForCategoryValue() {
    HashMap<BudgetCategory, Long> limits = new HashMap<>();
    limits.put(BudgetCategory.AST_NODE_VISIT, null);

    ResourceBudget budget = new ResourceBudget(ResourceBudget.UNLIMITED, limits);

    assertThat(budget.getCategoryLimit(BudgetCategory.AST_NODE_VISIT))
        .isEqualTo(ResourceBudget.UNLIMITED);
  }
}
