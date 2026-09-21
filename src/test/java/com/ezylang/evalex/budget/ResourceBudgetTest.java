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

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceBudgetTest {

  @Test
  void testDefaultBudgetIsUnbounded() {
    ResourceBudget budget = ResourceBudget.builder().build();

    assertThat(budget.isBounded()).isFalse();
    assertThat(budget.getTotalWeightLimit()).isEqualTo(ResourceBudget.UNLIMITED);
    for (BudgetCategory category : BudgetCategory.values()) {
      assertThat(budget.getLimit(category)).isEqualTo(ResourceBudget.UNLIMITED);
      assertThat(budget.getWeight(category)).isEqualTo(1L);
    }
  }

  @Test
  void testSharedUnboundedInstance() {
    assertThat(ResourceBudget.UNBOUNDED.isBounded()).isFalse();
  }

  @Test
  void testCategoryLimitMakesBudgetBounded() {
    ResourceBudget budget =
        ResourceBudget.builder().limit(BudgetCategory.FUNCTION_CALL, 10L).build();

    assertThat(budget.isBounded()).isTrue();
    assertThat(budget.getLimit(BudgetCategory.FUNCTION_CALL)).isEqualTo(10L);
    assertThat(budget.getLimit(BudgetCategory.NODE_VISIT)).isEqualTo(ResourceBudget.UNLIMITED);
  }

  @Test
  void testTotalWeightLimitMakesBudgetBounded() {
    ResourceBudget budget = ResourceBudget.builder().totalWeightLimit(5L).build();

    assertThat(budget.isBounded()).isTrue();
    assertThat(budget.getTotalWeightLimit()).isEqualTo(5L);
  }

  @Test
  void testCustomWeights() {
    ResourceBudget budget =
        ResourceBudget.builder()
            .weight(BudgetCategory.FUNCTION_CALL, 4L)
            .totalWeightLimit(100L)
            .build();

    assertThat(budget.getWeight(BudgetCategory.FUNCTION_CALL)).isEqualTo(4L);
    assertThat(budget.getWeight(BudgetCategory.NODE_VISIT)).isEqualTo(1L);
  }

  @Test
  void testLimitsSnapshotContainsAllCategories() {
    ResourceBudget budget = ResourceBudget.builder().limit(BudgetCategory.DATA_ACCESS, 2L).build();

    Map<BudgetCategory, Long> limits = budget.getLimits();

    assertThat(limits).hasSize(BudgetCategory.values().length);
    assertThat(limits.get(BudgetCategory.DATA_ACCESS)).isEqualTo(2L);
    assertThatThrownBy(() -> limits.put(BudgetCategory.DATA_ACCESS, 9L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testWeightsSnapshotIsUnmodifiable() {
    ResourceBudget budget = ResourceBudget.builder().build();

    assertThatThrownBy(() -> budget.getWeights().put(BudgetCategory.NODE_VISIT, 9L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testInvalidLimitRejected() {
    assertThatThrownBy(() -> ResourceBudget.builder().limit(BudgetCategory.NODE_VISIT, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Limit must be positive");
  }

  @Test
  void testInvalidWeightRejected() {
    assertThatThrownBy(() -> ResourceBudget.builder().weight(BudgetCategory.NODE_VISIT, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Weight must be positive");
  }

  @Test
  void testInvalidTotalWeightLimitRejected() {
    assertThatThrownBy(() -> ResourceBudget.builder().totalWeightLimit(-2L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Total weight limit must be positive");
  }
}
