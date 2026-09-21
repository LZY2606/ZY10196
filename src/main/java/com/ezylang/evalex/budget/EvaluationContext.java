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

import com.ezylang.evalex.EvaluationBudgetException;
import com.ezylang.evalex.EvaluationCancelledException;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.parser.ASTNode;
import com.ezylang.evalex.parser.Token;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable, per evaluation resource context. It owns the configured {@link ResourceBudget}, tracks
 * the consumed weight and category counts, the active function or operator call chain and supports
 * cooperative cancellation.
 *
 * <p>A context is activated on the current thread for the duration of a single {@code
 * Expression.evaluate(...)} call. Nested evaluations of other {@code Expression} instances,
 * including evaluations triggered by custom functions, reuse the active context and can not create
 * a fresh budget that bypasses the parent limits.
 *
 * <p>An {@link EvaluationContext} is not thread safe. Use one copy and therefore one evaluation per
 * thread, as with an {@code Expression} itself. Outside an active evaluation the {@link #current()}
 * method returns a no-op context whose accounting and cancellation methods do nothing, which keeps
 * the legacy default behavior unchanged.
 */
public class EvaluationContext {

  private static final ThreadLocal<Deque<EvaluationContext>> ACTIVE_CONTEXTS =
      ThreadLocal.withInitial(ArrayDeque::new);

  /** No-op context used when no evaluation or no budget is active on the current thread. */
  public static final EvaluationContext NO_OP =
      new EvaluationContext(ResourceBudget.UNLIMITED_BUDGET, false) {
        @Override
        public void charge(BudgetCategory category, Token token)
            throws EvaluationBudgetException, EvaluationCancelledException {}

        @Override
        public void charge(BudgetCategory category, long weight, Token token)
            throws EvaluationBudgetException, EvaluationCancelledException {}

        @Override
        public void checkCancelled(Token token) throws EvaluationCancelledException {}

        @Override
        public EvaluationValue evaluateLazyNode(
            ASTNode node, LazyNodeEvaluator evaluator, Token token)
            throws com.ezylang.evalex.EvaluationException {
          return evaluator.evaluate(node);
        }

        @Override
        public boolean isActive() {
          return false;
        }
      };

  private final ResourceBudget budget;

  private final AtomicLong totalWeight = new AtomicLong();

  private final EnumMap<BudgetCategory, AtomicLong> categoryCounts =
      new EnumMap<>(BudgetCategory.class);

  private final AtomicBoolean cancelled = new AtomicBoolean();

  private final boolean lazyCacheEnabled;

  private volatile String cancellationReason;

  private final Deque<String> callChain = new ArrayDeque<>();

  private final Map<ASTNode, EvaluationValue> lazyCache = new HashMap<>();

  /**
   * Creates a context for the given budget. Lazy parameter caching is enabled for limited budgets,
   * so repeated reads of the same lazy parameter are counted as {@link
   * BudgetCategory#LAZY_CACHE_HIT} events.
   *
   * @param budget The budget to enforce, must not be {@code null}.
   */
  public EvaluationContext(ResourceBudget budget) {
    this(budget, budget != null && !budget.isUnlimited());
  }

  private EvaluationContext(ResourceBudget budget, boolean lazyCacheEnabled) {
    if (budget == null) {
      throw new IllegalArgumentException("resource budget must not be null");
    }
    this.budget = budget;
    this.lazyCacheEnabled = lazyCacheEnabled;
    for (BudgetCategory category : BudgetCategory.values()) {
      categoryCounts.put(category, new AtomicLong());
    }
  }

  /**
   * Creates a context with an unlimited budget. Cancellation remains effective. This is useful to
   * make an evaluation cancellable without limiting any work.
   *
   * @return A new cancellable context without limits.
   */
  public static EvaluationContext cancellable() {
    return new EvaluationContext(ResourceBudget.UNLIMITED_BUDGET, false);
  }

  /**
   * Gets the active context on the current thread, or a no-op context when no evaluation is
   * running. The returned context is intended for custom functions, operators and data accessors
   * that need to account for their own work.
   *
   * @return The active {@link EvaluationContext}, never {@code null}.
   */
  public static EvaluationContext current() {
    Deque<EvaluationContext> stack = ACTIVE_CONTEXTS.get();
    EvaluationContext context = stack.peek();
    return context != null ? context : NO_OP;
  }

  /**
   * Gets the budget of this context.
   *
   * @return The immutable budget configuration.
   */
  public ResourceBudget getBudget() {
    return budget;
  }

  /**
   * Requests cancellation of the evaluation. The evaluation observes the request at its next budget
   * check point and then throws an {@link EvaluationCancelledException}.
   *
   * @param reason A reason for diagnostics, must not contain sensitive data values.
   */
  public void cancel(String reason) {
    cancellationReason = reason;
    cancelled.set(true);
  }

  /**
   * Checks if cancellation was requested.
   *
   * @return {@code true} after {@link #cancel(String)} was called.
   */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * Checks if this is an active evaluation context and not the no-op context.
   *
   * @return {@code true} if this context is active on the current thread.
   */
  public boolean isActive() {
    return true;
  }

  /**
   * Gets the cancellation reason.
   *
   * @return The reason passed to {@link #cancel(String)}, or {@code null}.
   */
  public String getCancellationReason() {
    return cancellationReason;
  }

  /**
   * Gets the consumed total weight so far.
   *
   * @return The non-negative total weight.
   */
  public long getConsumedTotalWeight() {
    return totalWeight.get();
  }

  /**
   * Gets the consumed count of one category so far.
   *
   * @param category The category to query.
   * @return The non-negative consumed count.
   */
  public long getConsumedCount(BudgetCategory category) {
    return categoryCounts.get(category).get();
  }

  /**
   * Gets an immutable snapshot of all consumed category counts.
   *
   * @return A map from category to consumed count.
   */
  public Map<BudgetCategory, Long> consumedByCategorySnapshot() {
    EnumMap<BudgetCategory, Long> snapshot = new EnumMap<>(BudgetCategory.class);
    for (Map.Entry<BudgetCategory, AtomicLong> entry : categoryCounts.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue().get());
    }
    return Collections.unmodifiableMap(snapshot);
  }

  /**
   * Gets a snapshot of the active function or operator call chain.
   *
   * @return An immutable list of frame labels, outermost first.
   */
  public List<String> callChainSnapshot() {
    synchronized (callChain) {
      return List.copyOf(callChain);
    }
  }

  /**
   * Checks for a pending cancellation and throws if one was requested.
   *
   * @param token The token at which the check happens, may be {@code null}.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void checkCancelled(Token token) throws EvaluationCancelledException {
    if (cancelled.get()) {
      throw new EvaluationCancelledException(
          token,
          cancellationReason,
          totalWeight.get(),
          consumedByCategorySnapshot(),
          callChainSnapshot());
    }
  }

  /**
   * Accounts one event of a category against the budget.
   *
   * @param category The event category.
   * @param token The current AST token for diagnostics, may be {@code null}.
   * @throws EvaluationBudgetException If the total weight or category limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void charge(BudgetCategory category, Token token)
      throws EvaluationBudgetException, EvaluationCancelledException {
    charge(category, 1L, token);
  }

  /**
   * Accounts a weighted event of a category against the budget.
   *
   * @param category The event category.
   * @param weight The weight to add, must be non-negative.
   * @param token The current AST token for diagnostics, may be {@code null}.
   * @throws EvaluationBudgetException If the total weight or category limit is exceeded.
   * @throws EvaluationCancelledException If cancellation was requested.
   */
  public void charge(BudgetCategory category, long weight, Token token)
      throws EvaluationBudgetException, EvaluationCancelledException {
    checkCancelled(token);
    if (weight < 0) {
      throw new IllegalArgumentException("Budget weight must not be negative");
    }

    long consumed = totalWeight.updateAndGet(current -> saturatedAdd(current, weight));
    long consumedForCategory = categoryCounts.get(category).addAndGet(weight);

    long categoryLimit = budget.getCategoryLimit(category);
    if (categoryLimit != ResourceBudget.UNLIMITED && consumedForCategory > categoryLimit) {
      throw budgetException(category, consumed, token);
    }
    long totalLimit = budget.getTotalWeightLimit();
    if (totalLimit != ResourceBudget.UNLIMITED && consumed > totalLimit) {
      throw budgetException(null, consumed, token);
    }
  }

  /**
   * Visits an AST node. Each eager visit is counted as {@link BudgetCategory#AST_NODE_VISIT}.
   *
   * @param token The token of the visited node, may be {@code null}.
   */
  public void enterNode(Token token)
      throws EvaluationBudgetException, EvaluationCancelledException {
    charge(BudgetCategory.AST_NODE_VISIT, token);
  }

  private EvaluationBudgetException budgetException(
      BudgetCategory category, long consumed, Token token) {
    return new EvaluationBudgetException(
        token,
        category,
        consumed,
        consumedByCategorySnapshot(),
        budget.getTotalWeightLimit(),
        budget.getCategoryLimits(),
        callChainSnapshot());
  }

  private static long saturatedAdd(long current, long weight) {
    long sum = current + weight;
    return sum >= 0 ? sum : Long.MAX_VALUE;
  }

  /**
   * Pushes a function or operator frame onto the call chain.
   *
   * @param frame The frame label.
   */
  public void pushCallFrame(String frame) {
    synchronized (callChain) {
      callChain.addLast(frame);
    }
  }

  /**
   * Removes the last pushed frame.
   *
   * @param frame The frame label to remove.
   */
  public void popCallFrame(String frame) {
    synchronized (callChain) {
      callChain.removeLastOccurrence(frame);
    }
  }

  /**
   * Evaluates a lazy parameter node, using the cached result of an earlier evaluation when the
   * budget tracks lazy parameters.
   *
   * <p>The first evaluation of a node is counted as {@link BudgetCategory#LAZY_EVALUATION}. Every
   * later read of the same cached node is counted as {@link BudgetCategory#LAZY_CACHE_HIT} and
   * returns the cached result without traversing the node again. When caching is disabled
   * (unlimited budgets or the no-op context), the node is always evaluated as in versions before
   * budgets existed.
   *
   * @param node The lazy parameter node.
   * @param evaluator The callback that performs the actual subtree evaluation.
   * @param token The token at which evaluation happens for diagnostics.
   * @return The evaluation result.
   * @throws com.ezylang.evalex.EvaluationException On evaluation, budget or cancellation errors.
   */
  public EvaluationValue evaluateLazyNode(ASTNode node, LazyNodeEvaluator evaluator, Token token)
      throws com.ezylang.evalex.EvaluationException {
    if (lazyCacheEnabled && lazyCache.containsKey(node)) {
      charge(BudgetCategory.LAZY_CACHE_HIT, token);
      return lazyCache.get(node);
    }
    charge(BudgetCategory.LAZY_EVALUATION, token);
    EvaluationValue result = evaluator.evaluate(node);
    if (lazyCacheEnabled) {
      lazyCache.put(node, result);
    }
    return result;
  }

  public void activate() {
    ACTIVE_CONTEXTS.get().push(this);
  }

  public void deactivate() {
    Deque<EvaluationContext> stack = ACTIVE_CONTEXTS.get();
    if (stack.peek() == this) {
      stack.pop();
    } else {
      stack.remove(this);
    }
    if (stack.isEmpty()) {
      ACTIVE_CONTEXTS.remove();
    }
  }

  /** Callback used by {@link #evaluateLazyNode(ASTNode, LazyNodeEvaluator, Token)}. */
  @FunctionalInterface
  public interface LazyNodeEvaluator {

    /**
     * Evaluates the given node.
     *
     * @param node The node to evaluate.
     * @return The evaluation result.
     * @throws com.ezylang.evalex.EvaluationException On evaluation errors.
     */
    EvaluationValue evaluate(ASTNode node) throws com.ezylang.evalex.EvaluationException;
  }
}
