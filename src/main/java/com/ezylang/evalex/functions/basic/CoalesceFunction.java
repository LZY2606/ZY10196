/*
  Copyright 2012-2022 Udo Klimaschewski

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
package com.ezylang.evalex.functions.basic;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.budget.BudgetCategory;
import com.ezylang.evalex.budget.EvaluationContext;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameter;
import com.ezylang.evalex.parser.Token;

/**
 * Returns the first non-null parameter, or {@link EvaluationValue#NULL_VALUE} if all parameters are
 * null.
 */
@FunctionParameter(name = "value", isVarArg = true)
public class CoalesceFunction extends AbstractFunction {

  @Override
  public EvaluationValue evaluate(
      Expression expression, Token functionToken, EvaluationValue... parameterValues)
      throws EvaluationException {

    for (EvaluationValue parameter : parameterValues) {
      EvaluationValue result = firstNonNull(parameter, functionToken);
      if (!result.isNullValue()) {
        return result;
      }
    }
    return EvaluationValue.NULL_VALUE;
  }

  private EvaluationValue firstNonNull(EvaluationValue value, Token functionToken)
      throws EvaluationException {
    if (!value.isArrayValue()) {
      return value.isNullValue() ? EvaluationValue.NULL_VALUE : value;
    }
    for (EvaluationValue element : value.getArrayValue()) {
      if (!element.isArrayValue()) {
        EvaluationContext.current().charge(BudgetCategory.COLLECTION_ELEMENT, functionToken);
      }
      if (!element.isNullValue()) {
        return element;
      }
    }
    return EvaluationValue.NULL_VALUE;
  }
}
