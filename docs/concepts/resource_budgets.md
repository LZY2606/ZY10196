---
layout: default
title: Resource Budgets and Cancellation
parent: Concepts
nav_order: 6
---

## Resource Budgets and Cancellation

Expressions can be deeply nested, iterate over large arrays or call custom functions recursively.
A resource budget limits the amount of work that a single evaluation can perform, and an
evaluation can be cancelled cooperatively, for example from another thread.

Budgets are opt-in. When no budget is configured, evaluation behaves exactly as in versions
without budget support and no evaluation context is created.

### Configuring a budget

A `ResourceBudget` is immutable and can limit a total weight and the number of counted events per
category. Every counted event adds a weight of one to the total weight. A limit of `-1`
(`ResourceBudget.UNLIMITED`) means that the limit is not enforced.

```java
ResourceBudget budget = ResourceBudget.builder()
    .totalWeightLimit(10_000L)
    .categoryLimit(BudgetCategory.FUNCTION_CALL, 100L)
    .categoryLimit(BudgetCategory.DATA_ACCESS, 500L)
    .build();

EvaluationContext context = new EvaluationContext(budget);
EvaluationValue result = expression.evaluate(context);
```

A budget can also be set on the `ExpressionConfiguration`. It then applies to every evaluation
started without an explicit context:

```java
ExpressionConfiguration configuration = ExpressionConfiguration.builder()
    .resourceBudget(budget)
    .build();
```

An explicit per evaluation context always takes precedence. When an expression is evaluated while
another evaluation is already active on the same thread (for example a custom function that
evaluates a nested `Expression`), the active context is reused. Nested expressions can therefore
not create a new budget that bypasses the limits of the parent evaluation.

### Counted categories

| Category | Counted when |
|---|---|
| `AST_NODE_VISIT` | A node of the expression AST is visited during eager evaluation. |
| `FUNCTION_CALL` | A built-in or custom function is invoked. |
| `OPERATOR_CALL` | A prefix, postfix or infix operator is invoked. |
| `LAZY_EVALUATION` | A lazy parameter is evaluated for the first time in an evaluation. |
| `LAZY_CACHE_HIT` | A previously evaluated lazy parameter is read again from the cache. |
| `COLLECTION_ELEMENT` | A leaf element of an array is traversed by a built-in aggregate function. |
| `DATA_ACCESS` | A variable is read from the configured `DataAccessorIfc`. Constants are not counted. |

Short-circuiting `&&`, `||` and the `IF` and `SWITCH` functions only visit the branches they take.
Untaken branches do not consume node visits or any other budget.

### Lazy parameter caching

When a limited budget is active, the result of a lazy parameter node is cached for the duration of
the evaluation. The first evaluation of a node is counted as `LAZY_EVALUATION`. Reading the same
node again returns the cached result and is counted as `LAZY_CACHE_HIT`; the node subtree is not
traversed a second time.

Without a budget, or with an unlimited budget, lazy nodes are evaluated on every read as before,
so existing custom functions that depend on re-evaluation (for example repeated calls to
`RANDOM()`) keep their behavior.

Use `Expression.evaluateLazyNode(ASTNode)` to evaluate lazy parameters with the same caching and
counting semantics as the built-in lazy functions and operators.

### Using a budget from custom functions, operators and accessors

Custom extensions access the active context through a static method. Outside an evaluation it
returns a no-op context, so accounting and cancellation calls are safe anywhere:

```java
EvaluationContext context = EvaluationContext.current();
context.charge(BudgetCategory.FUNCTION_CALL, 5L, functionToken);
context.checkCancelled(functionToken);
```

A data accessor can charge its reads with a `null` token, because accessor reads do not have an AST
token.

The current context is also available from an `Expression` through
`expression.getCurrentEvaluationContext()`.

### Cancellation

An evaluation can be made cancellable without a budget by using an unlimited context:

```java
EvaluationContext context = EvaluationContext.cancellable();

// from another thread or later in the evaluation:
context.cancel("user navigated away");
```

Cancellation is cooperative. A running custom function should periodically call
`checkCancelled(Token)` or `isCancelled()` so that it can observe a pending cancellation.

### Exceptions

Budget exhaustion throws `EvaluationBudgetException` and cancellation throws
`EvaluationCancelledException`. Both extend `EvaluationException`, so existing code that catches
`EvaluationException` continues to work, while the two conditions can also be handled separately.

Both exceptions expose the current AST position, the active function or operator call chain, the
consumed category counts and the configured limits. They do not contain variable or parameter
values, to avoid leaking sensitive data through error messages.

### Complexity and compatibility

- Each counted event adds a constant amount of work to the evaluation.
- Lazy caching adds a map lookup per lazy read and retains cached results until the evaluation
  ends.
- The context is stored in a thread local for the duration of an evaluation. Use one `Expression`
  copy and one context per thread, as documented for concurrent evaluation.
- Default behavior without a budget is unchanged, and no public method signature was removed.
