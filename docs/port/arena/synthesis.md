# Kit arena synthesis

## Candidates

- `kit-a` (claude-opus-5-5) dropped out. It was cancelled before writing a design or verifying a build.
- `kit-b` (gpt-6.1-sol): immutable data classes, persistent collections, selector observation, exhaustive `TestStore`. 869 kit lines. Compile and tests pass.
- `kit-c` (grok-4.7): one stable object per feature and per row, fields as Compose snapshot `StoreVar`s mutated only inside the reducer, effect reads rejected at runtime. 983 kit lines. Compile and tests pass.

## Cross-judge

gpt-6.1-sol, read-only, recommended `kit-b` as the base and grafting `kit-c`'s `Case` companions and sheet helpers. The judge shares a model family with `kit-b`. Its full verdict is in the session transcript.

## Decision

Base is `kit-c`. The deciding criterion is the user's long-term requirement: iOS reducers are mutation code (`state.items[id: id]?.liveText += delta`), and every future iOS diff has to port line by line. Under `kit-c` that line stays one line with the same shape. Under `kit-b` every mutation becomes a `copy` chain, a dropped `copy` result is a silent bug, and multi-step branches restructure. Streaming invalidation is narrow by construction in `kit-c` (a token writes one row's snapshot field).

The judge's findings against `kit-c` are real and are fixed by grafts, not accepted:

| Finding | Graft |
| --- | --- |
| Child namespaces replace instead of extend, so retiring Home leaves Session and Voice jobs running | Path-based namespaces from `kit-b`: a job is indexed at every ancestor, an ancestor can cancel a descendant's `CancelID`, and identity change cancels the whole subtree |
| `BindingAction` is a closure with no field identity, so `case .binding(\.draft)` cannot port | Typed bindings keyed by property, matchable in reducers and `onChange` |
| A cancellable `Merge` runs its bodies serially | Merged effects run concurrently |
| `IdentifiedArray.slots` is publicly mutable, plain fields bypass guards | Read-only views, and every state field is a `StoreVar` or an immutable value |
| `TestStore` exhaustivity only checks actions | Exhaustive state assertions: a field registry snapshots state before and after each send, the expected closure mutates a copy, unasserted changes fail |
| A cancelled effect can still publish a late action | Late-send rejection from `kit-b` |
| The root store lives in a `ViewModel` | Process-lifetime root owned by the `Application`, as `OpenCodeApp.store` is on iOS |
| No scoped stores for child views | `store.scope(state, action)` returning a child store view |

Also taken from `kit-b`: a flat product package (Swift has one module namespace, so moving a file between iOS folders never changes Kotlin imports), a separate wall clock for iOS's raw `Task.sleep` polls, fail-closed test dependencies, and the producer-cancellation risk the judge raised as a test case.

Freshness coalescing is feature logic in `SessionFeature`, not kit logic. It ports with that feature together with its two iOS tests.
