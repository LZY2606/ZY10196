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

/**
 * One entry of the function and operator call chain recorded by an {@link EvaluationContext}.
 *
 * <p>A frame only stores the name of the function or operator as it appears in the expression
 * syntax. Evaluated data values are never stored here.
 */
public final class CallFrame {

  /** What kind of call the frame represents. */
  public enum Kind {
    /** A function call. */
    FUNCTION,
    /** An operator call. */
    OPERATOR
  }

  private final Kind kind;
  private final String name;

  /**
   * Creates a new call frame.
   *
   * @param kind The call kind.
   * @param name The function or operator name from the expression syntax.
   */
  public CallFrame(Kind kind, String name) {
    this.kind = kind;
    this.name = name;
  }

  /**
   * @return The call kind.
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * @return The function or operator name.
   */
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return kind == Kind.FUNCTION ? name + "()" : name;
  }
}
