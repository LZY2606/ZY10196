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

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ASTNode;
import com.ezylang.evalex.parser.Token;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable, per-evaluation state for a {@link ResourceBudget}.
 *
 * <p>A context tracks consumed units per {@link BudgetCategory}, the active function and operator
 * call chain, the current AST position and the results of lazy parameters already evaluated. It
 * also supports cooperative cancellation through {@link #cancel()}.
 *
 * <p>The evaluator publishes the context for the running evaluation through a thread local. Nested
 * evaluations on the same thread, including evaluations triggered from a custom function or from a
 * variable that holds an expression node, join the active context and therefore can not create a
 * fresh budget that bypasses the parent limits. Each evaluation uses its own context, so the same
 * compiled {@link Expression} can be evaluated in parallel without sharing counters.
 *
 * <p>Custom functions, operators and data accessors can contribute to the budget through the static
 * helpers of this class, e.g. {@link #recordCollectionElement()} when iterating an array, or
 * through {@link #getCurrent()} while an evaluation is active.
 */
public final class EvaluationContext implements AutoCloseable {

  private static final ThreadLocal<Deque<EvaluationContext>> ACTIVE_CONTEXTS =
      ThreadLocal.withInitial(ArrayDeque::new);

  private final ResourceBudget budget;
  private final long[] consumed = new long[BudgetCategory.values().length];
  private final Deque<CallFrame> callChain = new ArrayDeque<>();
  private final IdentityHashMap<ASTNode, EvaluationValue> lazyCache = new IdentityHashMap<>();
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  private long totalWeight;
  private Token currentToken;

  /**
   * Creates a new context for the given budget.
   *
   * @param budget The budget to enforce, must not be {@code null}.
   */
  public EvaluationContext(ResourceBudget budget) {
    this.budget = Objects.requireNonNull(budget, "budget");
  }

  /**
   * Returns the currently active context, or {@code null} if the current thread is not evaluating
   * an expression.
   *
   * @return The active context or {@code null}.
   */
  public static EvaluationContext getCurrent() {
    return ACTIVE_CONTEXTS.get().peekFirst();
  }

  /**
   * Records one collection element traversal on the active context. Does nothing outside of an
   * evaluation. Intended for custom functions that iterate arrays or other collections.
   */
  public static void recordCollectionElement() {
    EvaluationContext context = getCurrent();
    if (context != null) {
      context.record(BudgetCategory.COLLECTION_ELEMENT);
    }
  }

  /**
   * Records one data accessor read on the active context. Does nothing outside of an evaluation.
   * Intended for custom functions or data accessors that perform additional variable reads.
   */
  public static void recordDataAccess() {
    EvaluationContext context = getCurrent();
    if (context != null) {
      context.record(BudgetCategory.DATA_ACCESS);
    }
  }

  /**
   * Pushes this context as the active context for the current thread.
   *
   * @return This context.
   */
  public EvaluationContext activate() {
    ACTIVE_CONTEXTS.get().push(this);
    return this;
  }

  /** Removes this context from the active contexts of the current thread. */
  @Override
  public void close() {
    Deque<EvaluationContext> stack = ACTIVE_CONTEXTS.get();
    if (stack.peekFirst() == this) {
      stack.pop();
    } else {
      stack.removeFirstOccurrence(this);
    }
    if (stack.isEmpty()) {
      ACTIVE_CONTEXTS.remove();
    }
  }

  /**
   * Requests cooperative cancellation of the evaluation. The next checked evaluation step throws an
   * {@link EvaluationCancelledException}. Calling this method is safe from any thread.
   */
  public void cancel() {
    cancelled.set(true);
  }

  /**
   * @return {@code true} if cancellation was requested through {@link #cancel()}.
   */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * @return The immutable budget enforced by this context.
   */
  public ResourceBudget getBudget() {
    return budget;
  }

  /**
   * Returns the consumed units for a category.
   *
   * @param category The category.
   * @return The consumed units so far.
   */
  public long getConsumed(BudgetCategory category) {
    return consumed[category.ordinal()];
  }

  /**
   * @return An unmodifiable snapshot of consumed units per category.
   */
  public Map<BudgetCategory, Long> getConsumed() {
    EnumMap<BudgetCategory, Long> map = new EnumMap<>(BudgetCategory.class);
    for (BudgetCategory category : BudgetCategory.values()) {
      map.put(category, consumed[category.ordinal()]);
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * @return The consumed total weight so far.
   */
  public long getConsumedTotalWeight() {
    return totalWeight;
  }

  /**
   * @return An unmodifiable snapshot of the active call chain, innermost frame first.
   */
  public List<CallFrame> getCallChain() {
    List<CallFrame> frames = new ArrayList<>(callChain.size());
    for (CallFrame frame : callChain) {
      frames.add(frame);
    }
    return Collections.unmodifiableList(frames);
  }

  /**
   * Sets the token currently being evaluated, used for diagnostics.
   *
   * @param token The current token, may be {@code null}.
   */
  public void setCurrentToken(Token token) {
    this.currentToken = token;
  }

  /**
   * Records one unit of work in the given category and enforces the category and total weight
   * limits.
   *
   * @param category The category of the consumed work.
   * @throws BudgetExceededException If a limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void record(BudgetCategory category) {
    record(category, 1L);
  }

  /**
   * Records units of work in the given category and enforces the category and total weight limits.
   *
   * @param category The category of the consumed work.
   * @param units The consumed units, must be positive.
   * @throws BudgetExceededException If a limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void record(BudgetCategory category, long units) {
    if (units <= 0) {
      throw new IllegalArgumentException("Recorded units must be positive");
    }
    checkCancelled();
    int ordinal = category.ordinal();
    long already = consumed[ordinal];
    long limit = budget.getLimit(category);
    if (limit != ResourceBudget.UNLIMITED && already + units > limit) {
      throw new BudgetExceededException(
          category,
          limit,
          already,
          units,
          currentTokenName(),
          currentTokenStart(),
          currentTokenEnd(),
          getCallChain(),
          getConsumed(),
          budget);
    }
    long weight = safeMultiply(budget.getWeight(category), units);
    long totalLimit = budget.getTotalWeightLimit();
    if (totalLimit != ResourceBudget.UNLIMITED && totalWeight + weight > totalLimit) {
      throw new BudgetExceededException(
          totalLimit,
          totalWeight,
          weight,
          currentTokenName(),
          currentTokenStart(),
          currentTokenEnd(),
          getCallChain(),
          getConsumed(),
          budget);
    }
    consumed[ordinal] = already + units;
    totalWeight += weight;
  }

  /**
   * Checks whether cancellation was requested, without consuming any budget.
   *
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void checkCancelled() {
    if (cancelled.get()) {
      throw new EvaluationCancelledException(
          currentTokenName(),
          currentTokenStart(),
          currentTokenEnd(),
          getCallChain(),
          getConsumed(),
          budget);
    }
  }

  /**
   * Pushes a function frame onto the call chain and records one {@link
   * BudgetCategory#FUNCTION_CALL}.
   *
   * @param functionToken The token of the called function.
   * @throws BudgetExceededException If the function call limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void enterFunction(Token functionToken) {
    currentToken = functionToken;
    record(BudgetCategory.FUNCTION_CALL);
    callChain.push(new CallFrame(CallFrame.Kind.FUNCTION, functionToken.getValue()));
  }

  /**
   * Pushes an operator frame onto the call chain and records one {@link
   * BudgetCategory#OPERATOR_CALL}.
   *
   * @param operatorToken The token of the called operator.
   * @throws BudgetExceededException If the operator call limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void enterOperator(Token operatorToken) {
    currentToken = operatorToken;
    record(BudgetCategory.OPERATOR_CALL);
    callChain.push(new CallFrame(CallFrame.Kind.OPERATOR, operatorToken.getValue()));
  }

  /** Removes the innermost frame from the call chain. */
  public void leaveCall() {
    if (!callChain.isEmpty()) {
      callChain.pop();
    }
  }

  /**
   * Evaluates a lazy parameter or operand, caching the result for the current evaluation.
   *
   * <p>The first evaluation of a node counts as {@link BudgetCategory#LAZY_EVALUATION} and the
   * subtree is evaluated through {@link Expression#evaluateSubtree(ASTNode)}, so its visited nodes,
   * function and operator calls are counted as usual. Repeated reads of the same node instance
   * return the cached result and count as {@link BudgetCategory#LAZY_CACHE_HIT}, without visiting
   * the node again.
   *
   * @param expression The expression used to evaluate the node on a cache miss.
   * @param node The lazy parameter node.
   * @return The evaluated or cached value.
   * @throws EvaluationException If evaluation fails.
   * @throws BudgetExceededException If a budget limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public EvaluationValue evaluateLazy(Expression expression, ASTNode node)
      throws EvaluationException {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(node, "node");
    if (lazyCache.containsKey(node)) {
      record(BudgetCategory.LAZY_CACHE_HIT);
      return lazyCache.get(node);
    }
    record(BudgetCategory.LAZY_EVALUATION);
    EvaluationValue value = expression.evaluateSubtree(node);
    lazyCache.put(node, value);
    return value;
  }

  private String currentTokenName() {
    return currentToken != null ? currentToken.getValue() : null;
  }

  private int currentTokenStart() {
    return currentToken != null ? currentToken.getStartPosition() : -1;
  }

  private int currentTokenEnd() {
    return currentToken != null
        ? currentToken.getStartPosition() + currentToken.getValue().length()
        : -1;
  }

  private static long safeMultiply(long left, long right) {
    long result = left * right;
    if (left != 0 && result / left != right) {
      return Long.MAX_VALUE;
    }
    return result;
  }
}
