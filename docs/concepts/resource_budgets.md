---
layout: default
title: Resource Budgets and Cancellation
parent: Concepts
nav_order: 6
---

## Resource Budgets and Cancellation

An evaluation can be limited by a `ResourceBudget` and cancelled cooperatively through an
`EvaluationContext`. Both live in the `com.ezylang.evalex.budget` package.

A budget bounds the work performed by a single `evaluate(...)` call. It is useful for expressions
that are deeply nested, evaluate large arrays or structures, or call custom functions that may
recurse. When no budget is configured, evaluation behaves exactly as in previous versions and no
work is counted.

### Counted Work

Each unit of work belongs to one of the `BudgetCategory` values:

| Category | Counted when |
|---|---|
| `NODE_VISIT` | An AST node is actually evaluated. |
| `FUNCTION_CALL` | A built-in or custom function is called. |
| `OPERATOR_CALL` | A built-in or custom operator is called. |
| `LAZY_EVALUATION` | A lazy parameter or operand is evaluated for the first time in an evaluation. |
| `LAZY_CACHE_HIT` | An already evaluated lazy node is read again and its cached result is returned. |
| `COLLECTION_ELEMENT` | One element is traversed while iterating an array, including array index access and the array handling of SUM, AVERAGE, MIN, MAX and COALESCE. |
| `DATA_ACCESS` | A variable value is read through the configured data accessor. Constants are not counted. |

Lazy parameters are cached for the evaluation. The first read of a node counts as
`LAZY_EVALUATION` and visits the subtree, so its nodes, function and operator calls are counted as
usual. Every later read of the same node returns the cached value, counts as `LAZY_CACHE_HIT` and
does not visit the node again.

### Short Circuiting

Branches that are not evaluated do not consume any node budget. This applies to:

- The unselected operand of `&&` and `||`.
- The unselected branches of `IF` and `SWITCH`.
- Any lazy parameter that a custom function or operator never reads.

### Configuring Limits

A budget accepts an independent limit for each category and a shared total weight limit. Every
consumed unit adds the weight of its category (default 1) to the total weight. Limits must be
positive. A budget without limits is unbounded.

```java
ResourceBudget budget =
    ResourceBudget.builder()
        .limit(BudgetCategory.FUNCTION_CALL, 1000)
        .limit(BudgetCategory.COLLECTION_ELEMENT, 100_000)
        .weight(BudgetCategory.FUNCTION_CALL, 10)
        .totalWeightLimit(1_000_000)
        .build();
```

A budget can be passed directly to an evaluation or set on the configuration:

```java
// Per evaluation
EvaluationValue result = expression.evaluate(budget);

// Default for all evaluations of expressions with this configuration
ExpressionConfiguration configuration =
    ExpressionConfiguration.builder().resourceBudget(budget).build();
Expression expression = new Expression("SUM(values)", configuration);
```

When both a configured budget and an explicit budget are present, the explicit one wins for a root
evaluation.

### Cancellation

An `EvaluationContext` carries the budget and the cancellation state. Pass it to an evaluation and
call `cancel()` from any thread. The next checked evaluation step throws an
`EvaluationCancelledException`.

```java
EvaluationContext context = new EvaluationContext(budget);
Future<?> future = executor.submit(() -> expression.evaluate(context));
// ... from another thread:
context.cancel();
```

After an evaluation finishes, the context exposes diagnostic counters, for example
`context.getConsumed(BudgetCategory.NODE_VISIT)`.

### Nested Evaluations

The active context is published for the evaluating thread. A nested evaluation on the same thread,
including a variable that holds an expression node, a custom function that creates and evaluates
another `Expression`, or a call to `evaluateSubtree`, joins the active context. A nested
evaluation can therefore not create a fresh budget to bypass the parent limits. Each evaluation
creates its own context, so the same compiled `Expression` can be evaluated in parallel threads with
independent counters.

### Exceptions

Budget and cancellation failures extend `BudgetControlException`, which is an unchecked
`RuntimeException`. They are distinct from the checked `EvaluationException` used for ordinary
evaluation errors.

- `BudgetExceededException` is thrown when a category limit or the total weight limit is exceeded.
  It reports the exceeded category (or that the total weight limit was hit), the configured limit,
  the consumed and requested units, the current token and position, the active call chain and the
  consumed units per category.
- `EvaluationCancelledException` is thrown after `cancel()`.

Diagnostic data contains token text, positions, function and operator names, counts and limits. It
never contains evaluated variable or parameter values.

### Use From Custom Extensions

Custom functions and operators receive the evaluating `Expression`. Evaluate lazy parameters with
`expression.evaluateLazyParameter(node)` so that repeated reads use the cache and the lazy
categories are counted correctly.

Custom code that iterates arrays or performs additional variable reads can record that work
through the static helpers:

```java
EvaluationContext.recordCollectionElement();
EvaluationContext.recordDataAccess();
```

The helpers use the active context and do nothing when called outside an evaluation. More control
is available through `EvaluationContext.getCurrent()`, which returns the active context or `null`.

### Complexity and Compatibility

Counting adds constant time and a small constant allocation overhead per evaluated node, call,
array element and data accessor read. The lazy result cache uses an identity map that is
discarded with the evaluation context. In the worst case it holds one entry per visited lazy node,
so its size is proportional to the evaluated expression size.

Budget enforcement changes no result values. Without a configured budget the only behavioral
difference is the presence of an internal per-evaluation context. Budget exceptions are unchecked,
so existing code that only handles `EvaluationException` compiles unchanged but should add handling
when budgets are enabled.
