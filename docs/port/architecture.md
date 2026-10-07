# Porting architecture

This is the reference every port follows. It maps the OpenCode iOS app (SwiftUI and The Composable Architecture 1.26) onto Kotlin, Jetpack Compose, and the kit in this repo. A ported file should read like its Swift original, so a later iOS diff applies line by line, in the same file names, type names, case names, and field names.

The kit is `app/src/main/java/ai/opencode/mobilecode/tca/`. Test support is `app/src/test/java/ai/opencode/mobilecode/tca/TestStore.kt`. The iOS vocabulary for views is `app/src/main/java/ai/opencode/mobilecode/system/` (see `docs/port/system.md`). Why the kit has this shape is in `docs/port/arena/synthesis.md`. The reference port is What's New: `WhatsNew/WhatsNewFeature.kt`, `WhatsNewEntries.kt`, `WhatsNewView.kt`, and `WhatsNewTests.kt`. When this page is silent, copy what those files do.

## Files and names

### Paths

| iOS file | Kotlin file |
| --- | --- |
| `OpenCode/<rel>.swift` | `app/src/main/java/ai/opencode/mobilecode/<rel>.kt` |
| `OpenCodeTests/<rel>.swift` | `app/src/test/java/ai/opencode/mobilecode/<rel>.kt` |
| `OpenCodeShare/<rel>.swift` | `app/src/main/java/ai/opencode/mobilecode/OpenCodeShare/<rel>.kt` |
| `OpenCodeWidgets/<rel>.swift` | `app/src/main/java/ai/opencode/mobilecode/OpenCodeWidgets/<rel>.kt` |

Keep the folder names, the base name, and its case. `OpenCode/WhatsNew/WhatsNewFeature.swift` is `app/src/main/java/ai/opencode/mobilecode/WhatsNew/WhatsNewFeature.kt`. A `+` in a Swift file name stays: `HomeFeature+ProblemReports.swift` is `HomeFeature+ProblemReports.kt`.

### One package

Every product file declares `package ai.opencode.mobilecode`, whatever folder it sits in. Swift has one module namespace, so moving a Swift file between folders moves the Kotlin file and changes no imports. The kit is `ai.opencode.mobilecode.tca` and the view vocabulary is `ai.opencode.mobilecode.system`.

### Names

- Type, nested type, property, function, enum case, and `CancelID` case names stay verbatim, including a lowercase first letter on enum cases and the casing of `ID` and `URL`.
- Escape a name that is a Kotlin hard keyword with backticks: `as`, `break`, `class`, `continue`, `do`, `else`, `false`, `for`, `fun`, `if`, `in`, `interface`, `is`, `null`, `object`, `package`, `return`, `super`, `this`, `throw`, `true`, `try`, `typealias`, `typeof`, `val`, `var`, `when`, `while`.
- A tag-only enum is an `enum class`. An enum with associated values is a `sealed interface` whose cases are `data class` or `data object`.
- An associated-value label is the constructor property name: `contentBatch(sessionID:events:)` is `data class contentBatch(val sessionID: String, val events: List<…>)`.
- An unlabeled payload takes the name the feature's reducer binds it to: `case let .buttonEnabledChanged(enabled)` makes `data class buttonEnabledChanged(val enabled: Boolean)`. A child feature's actions are `action`, and a binding is `binding`.
- A named tuple is a small `data class` with the tuple's labels.
- A case and a nested type in the same scope cannot differ only in case (`case delegate(Delegate)` inside `enum Action`): their class files collide on the macOS case-insensitive disk and one silently replaces the other. Declare the nested type one level up (`SessionSheetFeature.Delegate`) and keep the case name. `DeclarationNameCollisionTest` fails the build otherwise.

### Comments, conditions, and extensions

- Port Swift comments verbatim, with the same markers (`///` and `//`) in the same places.
- `#if DEBUG` is `if (BuildConfig.DEBUG)`.
- An extension in the same file as its type folds into the type. Its `static` members go into the companion object, as `WhatsNewEntry.all` does.
- An extension in another file stays in that other file as Kotlin extensions, so the iOS diff that touches it lands in the same Kotlin file.

### Files that are not ported yet

- When a ported file needs a type whose Swift home is not ported, port only that type into its home's Kotlin file, at its Swift position, with a partial marker: `@file:PortedFrom("OpenCode/HomeView.swift", "553a407", partial = "DrawerShortcutLabel")`. The porter of `HomeView.swift` completes the file and drops `partial`.
- A test that drives an unported feature stays in its file as `@Ignore("…")` with an empty body that names the file that will port it, like `homeOpensWhatsNewFromItsButton`. The test report lists it as skipped until then.

## The PortedFrom marker

A ported Kotlin file starts with the marker, before `package`:

```kotlin
@file:PortedFrom("OpenCode/WhatsNew/WhatsNewFeature.swift", "553a407")

package ai.opencode.mobilecode
```

The path is the iOS file. The commit is the upstream revision that file was last synced against. `scripts/ios-sync.py` reads the markers: `progress` lists ported files, `check` fails on unported, stale, or missing claims, and `plan` and `advance` drive a sync. When you port an upstream change to a file, set its commit to that revision. A marker with `partial = "<what is ported>"` keeps the iOS file counted as unported in `progress` and `check` while still mapping it for `plan`. Never claim a file in full that you ported in part.

## State

A feature's `@ObservableState struct State` is a class that extends `ObservableState`. The store owns one instance for the life of the feature, and the reducer mutates it in place, so a Swift mutation line stays one line.

| Swift | Kotlin |
| --- | --- |
| `@ObservableState struct State: Equatable` | `class State(…) : ObservableState()` |
| `var draft = ""` | `var draft by StoreVar("")` |
| `var entries: [WhatsNewEntry]` set in `init` | constructor parameter `entries` and `var entries by StoreVar(entries)` |
| `let id: String` | `val id: String` (an immutable value only) |
| `var child: Child.State?` | `var child by StoreVar<Child.State?>(null)` |
| `@Presents var destination: Destination.State?` | `var destination by Presents<Destination.State>()` |
| `@Shared(.whatsNewSeenIDs) var seenIDs: Set<String>` | `var seenIDs by Shared(SharedKey.whatsNewSeenIDs)` |
| `IdentifiedArrayOf<Row>` | `val rows = IdentifiedArray<String, Row>()` |
| `var unseenEntries: [WhatsNewEntry] { … }` | `val unseenEntries: List<WhatsNewEntry> get() = …` |
| `func markAllSeen()`, `mutating func reset()` | `fun markAllSeen()`, `fun reset()` |
| `init(…) { … }` | constructor parameters with defaults, then `init { … }` in Swift's order |
| `struct State: Identifiable { var id: Int { navigationID } }` | `: ObservableState(), Identifiable<Int>` with `override val id get() = navigationID` |

The rules that make this safe:

- A stored property is a `StoreVar`, a `Shared`, an `IdentifiedArray`, or a `val` holding an immutable value. A plain `var` fails the first time the state is compared or snapshotted.
- A `StoreVar` holds an immutable value: `String`, numbers, enums, Kotlin `List`, `Set`, and `Map`, data classes of `val`s, or a child state. Never a `MutableList`, an array, or a `ByteArray`.
- Only the reducer writes a state the store owns. A write from a view or an effect throws.
- Reading state inside `Effect.run` does not compile. Reading a captured state from an effect throws. Copy the value into a local first.
- A test or an initializer may set up a state before a store owns it, as `var state = SessionFeature.State(…); state.isExternallyActive = true` does in iOS tests.
- Equality is structural, like the synthesized `Equatable`. `state.home == HomeFeature.State(server = server, snapshot = snapshot)` compares stored properties.
- A struct that is not a feature state is a `data class` of `val`s. Swift code that mutates a local copy becomes `copy(…)`.

Swift value collections mutate in place. In Kotlin the value in the `StoreVar` is replaced:

| Swift | Kotlin |
| --- | --- |
| `state.ids.insert(id)` | `state.ids += id` |
| `state.ids.remove(id)` | `state.ids -= id` |
| `state.ids.formUnion(more)` | `state.ids += more` |
| `state.items.append(item)` | `state.items += item` |
| `state.items.append(contentsOf: more)` | `state.items += more` |
| `state.map[key] = value` | `state.map += (key to value)` |
| `state.map[key] = nil` | `state.map -= key` |
| `state.map[key, default: ""] += delta` | `state.map += (key to state.map.getOrElse(key) { "" } + delta)` |
| `state.items.removeAll { … }` | `state.items = state.items.filterNot { … }` |
| `state.items[index] = item` | `state.items = state.items.toMutableList().also { it[index] = item }` |
| `a.isSubset(of: b)` | `b.containsAll(a)` |
| `state.rows[id: id]?.liveText += delta` | `state.rows[id]?.liveText += delta` |
| `state.rows[id: id] = row`, `= nil` | `state.rows[id] = row`, `= null` |

Values map as follows:

| Swift | Kotlin |
| --- | --- |
| `Bool`, `Int`, `Double`, `String` | `Boolean`, `Int`, `Double`, `String` |
| `[T]`, `Set<T>`, `[K: V]`, `T?` | `List<T>`, `Set<T>`, `Map<K, V>`, `T?` |
| `Date` | `java.time.Instant` |
| `DateComponents` holding a calendar day | `java.time.LocalDate` |
| `UUID` | `java.util.UUID` |
| `Duration`, `TimeInterval` | `kotlin.time.Duration` |

## Reducers

| Swift | Kotlin |
| --- | --- |
| `@Reducer struct WhatsNewFeature` | `class WhatsNewFeature : Reducer<WhatsNewFeature.State, WhatsNewFeature.Action>` |
| `var body: some Reducer<State, Action> { A(); B() }` | `override val body: Reducer<State, Action> = combine(A(), B())` |
| `Reduce { state, action in switch action { … } }` | `Reduce { action -> when (action) { … } }`, with `state` available inside |
| `case .viewed:` … `return .none` | `Action.viewed -> { … Effect.none }` |
| `case let .buttonEnabledChanged(enabled):` | `is Action.buttonEnabledChanged ->`, reading `action.enabled` |
| `guard let session = state.session else { return .none }` | `val session = state.session ?: return@Reduce Effect.none` |
| `return contentBatch(&state, events)` | `contentBatch(state, events)`, a function taking `state: State` |
| `BindingReducer()` | `BindingReducer()` |
| `case .binding(\.draft):` | `is Action.binding if action.binding.keyPath == State::draft ->` |
| `case .binding:` | `is Action.binding ->` |
| `Scope(state: \.whatsNew, action: \.whatsNew) { WhatsNewFeature() }` | `Scope(state = State::whatsNew, action = Action.whatsNew) { WhatsNewFeature() }` |
| `.ifLet(\.home, action: \.home) { HomeFeature() }` | `.ifLet(State::home, Action.home) { HomeFeature() }` |
| `.ifLet(\.$sessionDetail, action: \.sessionDetail) { SessionFeature() }` | `.ifLet(State::sessionDetail, Action.sessionDetail) { SessionFeature() }` |
| `.onChange(of: \.server) { oldValue, newValue in Reduce { … } }` | `.onChange(of = State::server) { oldValue, newValue -> Reduce { … } }` |
| `@Dependency(\.continuousClock) var clock` | `private val clock by Dependency(DependencyValues::continuousClock)` |
| `@Dependency(\.date.now) var now` | `private val now by Dependency { it.date.now }` |
| `isTesting` | `isTesting` |

Actions:

| Swift | Kotlin |
| --- | --- |
| `enum Action: BindableAction, Equatable` | `sealed interface Action : BindableAction<State>` |
| `case binding(BindingAction<State>)` | `data class binding(override val binding: BindingAction<State>) : Action` |
| `case buttonTapped` | `data object buttonTapped : Action` |
| `case whatsNew(WhatsNewFeature.Action)` | `data class whatsNew(val action: WhatsNewFeature.Action) : Action { companion object : Case<Action, WhatsNewFeature.Action>({ whatsNew(it) }, { (it as? whatsNew)?.action }) }` |
| `case sessionDetail(PresentationAction<SessionFeature.Action>)` | the same, with `PresentationAction<SessionFeature.Action>` |
| `.presented(.sendTapped)`, `.dismiss` | `PresentationAction.presented(SessionFeature.Action.sendTapped)`, `PresentationAction.dismiss` |
| `\.shellOutput.presented` | `Action.shellOutput.presented` |
| `.binding(.set(\.isPresented, false))` | `Action.binding(BindingAction.set(State::isPresented, false))` |
| `enum CancelID { case watchdog }` | `enum class CancelID { watchdog }` |
| `enum CancelID: Hashable { case workCall(String) }` | `sealed interface CancelID { data class workCall(val id: String) : CancelID }` |

A case that carries a child feature's actions declares a `Case` companion object, so `Scope`, `ifLet`, and `store.scope` read like their Swift call sites. A presentation case's type, `PresentationAction<…>`, selects the `@Presents` behavior of `ifLet`.

Composition keeps TCA's order, and the order in `body` is behavior:

- `ifLet` reduces the child before the parent, so the parent sees the child's mutations. When the parent clears the child, or swaps in one with a different `Identifiable.id`, every effect the child started is cancelled, at any depth below it. A child that is not `Identifiable` loses its effects only when it becomes null. This is how `navigationID` decides an effect's lifetime.
- On `.dismiss` the parent reduces first and can still read the child. Then the child is cleared, unless the parent already replaced it.
- `Scope` runs where it is declared and never cancels.
- `onChange` runs after the reducer it wraps, only when the selected value changed. A binding that writes an equal value is not a change. The selector must return an immutable value, never a feature state.
- The reducers in `combine(…)` run in order, and their effects merge in order.

## Effects

| Swift | Kotlin |
| --- | --- |
| `.none` | `Effect.none` |
| `.send(.refreshRequested)` | `Effect.send(Action.refreshRequested)` |
| `.run { send in … }` | `Effect.run { send -> … }` |
| `.run { send in … } catch: { error, send in … }` | `Effect.run(catch = { error, send -> … }) { send -> … }` |
| `await send(.watchdogTick(sessionID))` | `send(Action.watchdogTick(sessionID))` |
| `.merge(a, b)` | `Effect.merge(a, b)` |
| `.cancellable(id: CancelID.watchdog, cancelInFlight: true)` | `.cancellable(CancelID.watchdog, cancelInFlight = true)` |
| `.cancel(id: CancelID.watchdog)` | `Effect.cancel(CancelID.watchdog)` |
| `try await clock.sleep(for: .milliseconds(200))` | `clock.sleep(200.milliseconds)` |
| `try await Task.sleep(for: .seconds(2))` | `wallClock.sleep(2.seconds)` with `private val wallClock by Dependency(DependencyValues::wallClock)` |
| `let sessionID = state.session.id` or `[sessionID = state.session.id]` | `val sessionID = state.session.id`, before `Effect.run` |
| `Task.isCancelled` | `!currentCoroutineContext().isActive` |
| a `.run` that never sends, followed by `.cancellable` | `Effect.run<Action> { _ -> … }.cancellable(…)`, naming the action type Kotlin cannot infer |

The runtime rules:

- An effect body runs on the main thread. A client that blocks or does I/O switches with `withContext(Dispatchers.IO)` inside the client, never in the reducer.
- `.send` is delivered right after the current action, before any `.run` effect starts, as in TCA.
- Merged effects run concurrently.
- `cancellable(id)` indexes the effect under its ID at the feature that returned it and at every ancestor. `Effect.cancel(id)` from a feature cancels that ID there and in every descendant, so a parent can cancel a child's ID.
- `cancelInFlight = true` cancels the running effect with that ID before the new one starts. Without it, a second effect with the same ID runs beside the first. Freshness reads rely on that.
- A cancelled effect cannot send. A send after cancellation is dropped, even from client code that catches the cancellation.
- An effect produced by an action that another effect sent is independent of that effect. Cancelling the producer does not cancel it.
- The `StoreTask` that `send` returns covers the effects of that action and of every action those effects send. `task.cancel()` stops them all.
- An effect that throws without `catch` is logged in the app and fails the test.
- iOS runs the prompt POST in an unstructured `Task` so that leaving the screen cancels the reporting and not the delivery (`docs/port/explore/state.md`, item 9). Port it as a process-owned dependency that runs the request in its own scope and returns the result. The effect awaits it, and cancelling the effect cancels only the wait.

## Dependencies

A client is a class of function properties, one per endpoint, so a test overrides one endpoint at a time. Its companion object is the `DependencyKey`, and an extension property names it in `DependencyValues`:

```kotlin
class HapticsClient(
    var selection: () -> Unit,
    var success: () -> Unit,
) {
    companion object : DependencyKey<HapticsClient> {
        override val liveValue get() = HapticsClient(selection = { … }, success = { … })
        override val testValue get() = HapticsClient(selection = {}, success = {})
    }
}

var DependencyValues.haptics: HapticsClient
    get() = this[HapticsClient]
    set(value) {
        this[HapticsClient] = value
    }
```

- Declare both values with `get() =`. Each root container builds its value once, so a test double is shared within one test and never across tests.
- A key without a test value throws `UnimplementedDependency` in tests. A test value fails closed per endpoint. Do not copy iOS defaults that call the live client in tests, such as `OpenCodeClient.testValue`.
- The kit provides `continuousClock`, `wallClock`, `date`, and `uuid`. The continuous clock is the one `TestClock` drives. The wall clock is iOS's raw `Task.sleep`, real time even in tests.

## Shared state

| Swift | Kotlin |
| --- | --- |
| `@Shared(.whatsNewSeenIDs) var seenIDs` | `var seenIDs by Shared(SharedKey.whatsNewSeenIDs)` |
| `$seenIDs.withLock { $0.formUnion(ids) }` | `::seenIDs.withLock { value += ids }` |
| `state.$isButtonEnabled.withLock { $0 = false }` | `state::isButtonEnabled.withLock { value = false }` |
| a local `@Shared(.k) var x` and `$x.withLock { $0 = v }` | a local `var x by Shared(SharedKey.k)` and `x = v` |
| `Self[.fileStorage(.documentsDirectory.appending(component: "f.json")), default: []]` | `SharedKey.fileStorage(StorageDirectory.documents.appending(component = "f.json"), default = emptySet())` |
| `Self[.appStorage("whats-new-button-enabled"), default: true]` | `SharedKey.appStorage("whats-new-button-enabled", default = true)` |
| `Self[.inMemory("upload-unsupported-servers"), default: []]` | `SharedKey.inMemory("upload-unsupported-servers", default = emptySet())` |
| a keychain key | `SharedKey.keychain("saved-server", default = null)` |

Declare a key the way Swift extends `SharedKey`, in the same file:

```kotlin
val SharedKey.Companion.whatsNewButtonEnabled: SharedKey<Boolean>
    get() = SharedKey.appStorage("whats-new-button-enabled", default = true)
```

| Strategy | On Android |
| --- | --- |
| `appStorage` | `SharedPreferences` file `ai.opencode.mobilecode.app-storage`, one JSON string per key |
| `fileStorage` | a JSON file under `filesDir` (documents and application support) or `cacheDir`, replaced by an atomic rename on a background thread |
| `inMemory` | the process only |
| `keychain` | AES-256-GCM sealed under an Android Keystore key, bound to the account name, in `SharedPreferences` file `ai.opencode.mobilecode.keychain` |

- The value type must be serializable with kotlinx.serialization. Mark data classes `@Serializable`.
- A write is visible at once to every holder of the key, with no action. Settings writes keys straight from a view. An exhaustive test must assert a shared write like any other change.
- A value that fails to decode loads the key's default.
- `OpenCodeApp.onCreate` installs the live storage before any state exists. Tests get empty storage per test from `TestDependencies`.
- `ProtectedTranscriptCacheKey` has no strategy yet. Its porter adds one to `Shared.kt`, with the 500 ms coalesced writer on the wall clock.

## The store, threading, and lifetime

- `OpenCodeApp` owns the root store for the life of the process, as `OpenCodeApp.store` does on iOS. The AppFeature porter adds `val store by lazy { Store(initialState = { AppFeature.State() }) { AppFeature() } }` to its companion object. A configuration change never rebuilds the store or cancels its effects.
- Process death drops the store and its effects. The next launch builds state from `Shared` before the first frame, as `AppFeature.State.init` does.
- `store.send` runs on the main thread. Effects send through their `Send`, which returns to the main thread.
- SSE decoding and the `EventBatcher` stay in the client, off the main thread. A Home effect collects the batched flow and sends one action per batch.

## Views

A SwiftUI view is a composable function with the same name. Its stored properties are parameters. A view that SwiftUI modifiers style takes `modifier: Modifier = Modifier` as its first optional parameter.

| Swift | Kotlin |
| --- | --- |
| `let store: StoreOf<WhatsNewFeature>` | `store: Store<WhatsNewFeature.State, WhatsNewFeature.Action>` |
| `store.entries` | `store.state.entries` |
| `store.send(.viewed)` | `store.send(WhatsNewFeature.Action.viewed)` |
| `$store.isPresented` | `store.binding(WhatsNewFeature.State::isPresented, WhatsNewFeature.Action::binding)` |
| `Binding(get: { … }, set: { … })` | `Binding(getter = { … }, setter = { … })` |
| `store.scope(state: \.whatsNew, action: \.whatsNew)` | `store.scope(State::whatsNew, Action.whatsNew)` |
| `if let homeStore = store.scope(\.home, action: \.home) { HomeView(store: homeStore) }` | `store.scope(State::home, Action.home)?.let { homeStore -> HomeView(store = homeStore) }` |
| `.task { store.send(.task) }` | `store.task { store.send(Action.task) }` |
| `.task(id: store.session?.id) { store.send(.task) }` | `store.task(id = store.state.session?.id) { store.send(Action.task) }` |
| `.onAppear { store.send(.viewed) }` | `store.onAppear { store.send(Action.viewed) }` |
| `.sheet(isPresented: $store.isPresented) { … }` | `sheet(isPresented = store.binding(…)) { … }` |
| `.sheet(item: $store.scope(\.shellOutput, action: \.shellOutput)) { shellStore in … }` | `store.presented(State::shellOutput, Action.shellOutput) { shellStore -> … }` |
| `.alert("Title", isPresented: …) { Button("OK") {} } message: { Text(message) }` | `alert("Title", isPresented = …, message = message, buttons = listOf(AlertButton("OK")))` |
| `@Environment(\.dismiss) private var dismiss` | `val dismiss = LocalDismiss.current` |
| `@Environment(\.colorScheme)` and `colorScheme == .dark` | `isSystemInDarkTheme()` |
| `struct WhatsNewSheet: ViewModifier` | `@Composable fun WhatsNewSheet(store: …, content: @Composable () -> Unit)` that calls `content()` first |
| `.modifier(WhatsNewSheet(store: …))` | `WhatsNewSheet(store = …) { … }` |
| a computed view property such as `private var days` | a private extension property on the store, or a private function |

`store.task` runs on appear and again when `id` changes. An activity rebuilt by a configuration change does not run it again, because a SwiftUI view that stayed on screen would not. It never cancels effects, which matches the iOS views that send and return.

Layout and style:

| Swift | Kotlin |
| --- | --- |
| `HStack(alignment: .top, spacing: 14)` | `Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top)` |
| `VStack(alignment: .leading, spacing: 4)` | `Column(verticalArrangement = Arrangement.spacedBy(4.dp))` |
| `ZStack(alignment: .bottomTrailing)` | `Box(contentAlignment = Alignment.BottomEnd)` |
| `HStack(alignment: .firstTextBaseline)` | `Modifier.alignByBaseline()` on each text in the `Row` |
| `Spacer()`, `Spacer(minLength: 0)` | `Spacer(Modifier.weight(1f))` |
| `.padding(.vertical, 6)` | `Modifier.padding(vertical = 6.dp)` |
| `.frame(width: 48, height: 48)` | `Modifier.size(48.dp)` |
| `.frame(height: 18)` around smaller content | `Modifier.height(18.dp).wrapContentHeight()` |
| `.frame(maxWidth: .infinity, minHeight: 44)` | `Modifier.fillMaxWidth().heightIn(min = 44.dp)` |
| `.background(Color.primary.opacity(0.06), in: .rect(cornerRadius: 12))` | `Modifier.background(SystemColors.label.copy(alpha = 0.06f), RoundedCornerShape(12.dp))` |
| `.background(Color.black, in: .capsule)`, `Circle().fill(.red)` | `Modifier.background(Color.Black, CircleShape)`, `Modifier.background(SystemColors.systemRed, CircleShape)` |
| `.offset(x: -4, y: -4)` | `Modifier.offset(x = (-4).dp, y = (-4).dp)` |
| `.opacity(0.6)` | `Modifier.alpha(0.6f)` |
| `Text(title).font(.headline)` | `Text(title, style = TextStyles.headline)` |
| `.foregroundStyle(.secondary)`, `.tertiary`, `.tint` | `color = SystemColors.secondaryLabel`, `SystemColors.tertiaryLabel`, `SystemColors.tint` |
| `Color(.systemBackground)`, `Color(.label)`, `.red` | `SystemColors.systemBackground`, `SystemColors.label`, `SystemColors.systemRed` |
| `Image(systemName: "gift")` | `SystemImage("gift", contentDescription = null)` |
| an image with `.font(.system(size: 20, weight: .medium))` | `Modifier.symbolSize(20)` |
| an image with `.font(.footnote)` | `Modifier.symbolSize(TextStyles.footnote)` |
| `Canvas { context, size in … }` | `Canvas(modifier) { … }` |
| `@ScaledMetric(relativeTo: .body) var symbolWidth: CGFloat = 20` | `val symbolWidth = with(LocalDensity.current) { 20.sp.toDp() }` |

Lists, navigation, and accessibility:

| Swift | Kotlin |
| --- | --- |
| `List { Section { rows } header: { Text(title) } }` | `GroupedList { Section(header = { Text(title) }) { rows } }` |
| `ForEach(days, id: \.day) { day in Section { … } }` | `ForEach(store.days, id = { it.day }) { day -> Section { … } }` |
| `ForEach(day.entries) { WhatsNewRow(entry: $0) }` | `ForEach(day.entries) { WhatsNewRow(entry = it) }` |
| `.listSectionSpacing(.compact)` | `GroupedList(sectionSpacing = ListSectionSpacing.compact)` |
| `.scrollContentBackground(.hidden)`, `.modifier(SheetGlassBackground())` | `GroupedList(scrollContentBackground = false)` |
| a separator aligned under a row's first text | `Modifier.listRowSeparatorLeading()` on that text |
| `NavigationStack { root }` | `NavigationStack { root }` |
| `.navigationTitle("What’s New")` and `.navigationBarTitleDisplayMode(.inline)` | `NavigationTitle("What’s New", displayMode = TitleDisplayMode.inline)` |
| `.toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }` | `Toolbar { ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) { SystemButton("Done") { dismiss() } } }` |
| `.contextMenu { Button("Hide", systemImage: "eye.slash", action: onHide) }` | `SystemContextMenu(sections = listOf(SystemMenuSection(listOf(SystemMenuItem("Hide", systemImage = "eye.slash", onClick = onHide)))))` |
| `.accessibilityIdentifier("whats-new-list")` | `Modifier.testTag("whats-new-list")` |
| `.accessibilityLabel("What’s New")` | `Modifier.semantics { contentDescription = "What’s New" }` |
| `.accessibilityAction(named: "Hide", onHide)` | `customActions = listOf(CustomAccessibilityAction("Hide") { … })` inside `semantics` |
| `.accessibilityElement(children: .combine)` | `Modifier.semantics(mergeDescendants = true) {}` |
| `.accessibilityHidden(true)` | `Modifier.clearAndSetSemantics {}` |

These SwiftUI modifiers have no Compose line: `.buttonStyle(.plain)`, `.contentShape(.contextMenuPreview, …)`, `.fixedSize(horizontal: false, vertical: true)` (Compose text wraps), and `.presentationDetents([.medium, .large])` with `.presentationDragIndicator(.visible)`, which are what `SystemSheet` already does.

Observation is per property:

- A composable recomposes for the `StoreVar`s it read and nothing else. Split views where iOS splits them. Never read a whole state into one value at a screen root.
- A transcript row reads its own row object, so a token delta recomposes that row only. `RecompositionTest` proves it: 120 deltas recompose one row 120 times while the composer and a drawer row compose once.
- `store.observe { showsButton }` reads a derived value and recomposes only when the derived value changes.
- A view never writes state. It sends an action or sets a binding.

## Tests

| Swift | Kotlin |
| --- | --- |
| `@MainActor struct WhatsNewTests` | `class WhatsNewTests` with `@get:Rule val dependencies = TestDependencies()` |
| `@Test func openingMarksEverythingSeen() async` | `@Test fun openingMarksEverythingSeen()` |
| `#expect(store.state.showsButton)` | `expect(store.state.showsButton)` |
| `#expect(ids == ["a"], "Hiding doesn’t mark anything seen")` | `expect(ids == listOf("a"), "Hiding doesn’t mark anything seen")` |
| `TestStore(initialState: State(…)) { Feature() }` | `TestStore(initialState = { State(…) }) { Feature() }` |
| `… withDependencies: { $0.continuousClock = clock }` | `TestStore(initialState = { … }, withDependencies = { it.continuousClock = clock }) { Feature() }` |
| `await store.send(.buttonTapped) { $0.isPresented = true }` | `store.send(Action.buttonTapped) { it.isPresented = true }` |
| `$0.$seenIDs.withLock { $0 = ["a", "b"] }` | `it::seenIDs.withLock { value = setOf("a", "b") }` |
| `await store.receive(.watchdogTick(id)) { … }` | `store.receive(Action.watchdogTick(id)) { … }` |
| `await store.receive(\.healthResponse) { … }` | `store.receive<Action.healthResponse> { … }` |
| `await store.receive(\.sessionDetail.refreshResponse)` | `store.receive({ it is Action.sessionDetail && … })` |
| `let clock = TestClock()` and `await clock.advance(by: .milliseconds(100))` | `val clock = TestClock()` and `clock.advance(by = 100.milliseconds)` |
| `store.exhaustivity = .off`, `.off(showSkippedAssertions: false)` | `store.exhaustivity = Exhaustivity.off`, `Exhaustivity.off(showSkippedAssertions = false)` |
| `await store.finish()`, `skipReceivedActions()`, `skipInFlightEffects()` | `store.finish()`, `store.skipReceivedActions()`, `store.skipInFlightEffects()` |
| `let task = await store.send(.task)` and `await task.cancel()` | `val task = store.send(Action.task)` and `task.cancel()` |

How the `TestStore` checks:

- It is exhaustive. Each `send` and `receive` compares the whole state, shared values included, with the state before that action as the trailing closure changed it. A change the closure does not make fails with a diff.
- A shared write made between actions, by an effect or by the test body, must be asserted in the next `send` or `receive`, or with `store.assert { … }`. The end of the test fails otherwise.
- With `Exhaustivity.off`, the closure starts from the state after the action and only what it sets is checked, as in TCA.
- Effect actions arrive in order and must each be received. Sending while one waits fails.
- The test thread runs effects. `receive` and `finish` wait up to `timeout`, one second, for real asynchronous work.
- When the test ends, `TestDependencies` fails a store that still has unreceived actions or running effects, as a Swift `TestStore` does when it deinitializes.
- `TestDependencies` gives each test its own dependencies and empty shared storage. A state built in the test body and a `TestStore` share that storage, as on iOS.

A screenshot test records with Roborazzi (`docs/port/screenshots.md`). For anything inside a sheet or a dialog, capture dark mode with `@Config(qualifiers = "+night")`. `ForcedAppearance` does not reach another window. `WhatsNewViewScreenshotTest` is the example.

## What's New, side by side

The four files of What's New at 553a407, beside their Kotlin ports.

State, a `@Shared` key used from a method, and the once-per-process lab hook:

```swift
@ObservableState
struct State: Equatable {
  var entries: [WhatsNewEntry]
  @Shared(.whatsNewSeenIDs) var seenIDs: Set<String>
  @Shared(.whatsNewButtonEnabled) var isButtonEnabled: Bool
  var isPresented = false

  init(entries: [WhatsNewEntry] = WhatsNewEntry.all) {
#if DEBUG
    WhatsNewFeature.applyLabLaunchState()
#endif
    self.entries = entries
  }

  var unseenEntries: [WhatsNewEntry] { entries.filter { !seenIDs.contains($0.id) } }
  var showsButton: Bool { isButtonEnabled && !unseenEntries.isEmpty }

  func markAllSeen() {
    let ids = Set(entries.map(\.id))
    guard !ids.isSubset(of: seenIDs) else { return }
    $seenIDs.withLock { $0.formUnion(ids) }
  }
}
```

```kotlin
class State(entries: List<WhatsNewEntry> = WhatsNewEntry.all) : ObservableState() {
    var entries by StoreVar(entries)
    var seenIDs by Shared(SharedKey.whatsNewSeenIDs)
    var isButtonEnabled by Shared(SharedKey.whatsNewButtonEnabled)
    var isPresented by StoreVar(false)

    init {
        if (BuildConfig.DEBUG) {
            WhatsNewFeature.applyLabLaunchState()
        }
    }

    val unseenEntries: List<WhatsNewEntry> get() = entries.filter { !seenIDs.contains(it.id) }
    val showsButton: Boolean get() = isButtonEnabled && !unseenEntries.isEmpty()

    fun markAllSeen() {
        val ids = entries.map { it.id }.toSet()
        if (seenIDs.containsAll(ids)) return
        ::seenIDs.withLock { value += ids }
    }
}
```

Actions and the reducer:

```swift
enum Action: BindableAction, Equatable {
  case binding(BindingAction<State>)
  case buttonTapped
  case hideTapped
  case buttonEnabledChanged(Bool)
  /// The list appeared, in the sheet or in Settings.
  case viewed
}

var body: some Reducer<State, Action> {
  BindingReducer()
  Reduce { state, action in
    switch action {
    case .binding:
      return .none
    case .buttonTapped:
      state.isPresented = true
      state.markAllSeen()
      return .none
    case .hideTapped:
      state.$isButtonEnabled.withLock { $0 = false }
      return .none
    case let .buttonEnabledChanged(enabled):
      state.$isButtonEnabled.withLock { $0 = enabled }
      return .none
    case .viewed:
      state.markAllSeen()
      return .none
    }
  }
}
```

```kotlin
sealed interface Action : BindableAction<State> {
    data class binding(override val binding: BindingAction<State>) : Action

    data object buttonTapped : Action

    data object hideTapped : Action

    data class buttonEnabledChanged(val enabled: Boolean) : Action

    /// The list appeared, in the sheet or in Settings.
    data object viewed : Action
}

override val body: Reducer<State, Action> =
    combine(
        BindingReducer(),
        Reduce { action ->
            when (action) {
                is Action.binding -> Effect.none
                Action.buttonTapped -> {
                    state.isPresented = true
                    state.markAllSeen()
                    Effect.none
                }
                Action.hideTapped -> {
                    state::isButtonEnabled.withLock { value = false }
                    Effect.none
                }
                is Action.buttonEnabledChanged -> {
                    state::isButtonEnabled.withLock { value = action.enabled }
                    Effect.none
                }
                Action.viewed -> {
                    state.markAllSeen()
                    Effect.none
                }
            }
        },
    )
```

A shared key:

```swift
extension SharedKey where Self == FileStorageKey<Set<String>>.Default {
  /// What’s New entries already shown, by ID.
  static var whatsNewSeenIDs: Self {
    Self[.fileStorage(.documentsDirectory.appending(component: "whats-new-seen.json")), default: []]
  }
}
```

```kotlin
/// What’s New entries already shown, by ID.
val SharedKey.Companion.whatsNewSeenIDs: SharedKey<Set<String>>
    get() = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "whats-new-seen.json"), default = emptySet())
```

An exhaustive test that asserts a shared write:

```swift
@Test
func openingMarksEverythingSeen() async {
  let store = TestStore(initialState: WhatsNewFeature.State(entries: [entry("b"), entry("a")])) {
    WhatsNewFeature()
  }
  #expect(store.state.showsButton)

  await store.send(.buttonTapped) {
    $0.isPresented = true
    $0.$seenIDs.withLock { $0 = ["a", "b"] }
  }
  #expect(!store.state.showsButton)
}
```

```kotlin
@Test
fun openingMarksEverythingSeen() {
    val store = TestStore(initialState = { WhatsNewFeature.State(entries = listOf(entry("b"), entry("a"))) }) {
        WhatsNewFeature()
    }
    expect(store.state.showsButton)

    store.send(Action.buttonTapped) {
        it.isPresented = true
        it::seenIDs.withLock { value = setOf("a", "b") }
    }
    expect(!store.state.showsButton)
}
```

The sheet, a navigation bar declared from inside the list, and a grouped list:

```swift
struct WhatsNewSheet: ViewModifier {
  @Bindable var store: StoreOf<WhatsNewFeature>

  func body(content: Content) -> some View {
    content.sheet(isPresented: $store.isPresented) {
      WhatsNewSheetContent(store: store)
    }
  }
}

var body: some View {
  NavigationStack {
    WhatsNewList(store: store)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
        }
      }
  }
  .presentationDetents([.medium, .large])
  .presentationDragIndicator(.visible)
}

var body: some View {
  List {
    ForEach(days, id: \.day) { day in
      Section {
        ForEach(day.entries) { WhatsNewRow(entry: $0) }
      } header: {
        if let date = Calendar.current.date(from: day.day) {
          Text(date, format: .dateTime.month(.wide).day())
        }
      }
    }
  }
  .listSectionSpacing(.compact)
  .modifier(SheetGlassBackground())
  .navigationTitle("What’s New")
  .navigationBarTitleDisplayMode(.inline)
  .accessibilityIdentifier("whats-new-list")
  .onAppear { store.send(.viewed) }
}
```

```kotlin
@Composable
fun WhatsNewSheet(
    store: Store<WhatsNewFeature.State, WhatsNewFeature.Action>,
    content: @Composable () -> Unit,
) {
    content()
    sheet(isPresented = store.binding(WhatsNewFeature.State::isPresented, WhatsNewFeature.Action::binding)) {
        WhatsNewSheetContent(store = store)
    }
}

NavigationStack {
    WhatsNewList(store = store)
    Toolbar {
        ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
            SystemButton("Done") { dismiss() }
        }
    }
}

GroupedList(
    modifier = Modifier.testTag("whats-new-list"),
    sectionSpacing = ListSectionSpacing.compact,
    scrollContentBackground = false,
) {
    ForEach(store.days, id = { it.day }) { day ->
        Section(header = { Text(day.day.format(monthDay)) }) {
            ForEach(day.entries) { WhatsNewRow(entry = it) }
        }
    }
}
NavigationTitle("What’s New", displayMode = TitleDisplayMode.inline)
store.onAppear { store.send(WhatsNewFeature.Action.viewed) }
```

A row:

```swift
HStack(alignment: .top, spacing: 14) {
  WhatsNewIllustration(kind: entry.illustration)
  VStack(alignment: .leading, spacing: 4) {
    Text(entry.title)
      .font(.headline)
    Text(entry.summary)
      .font(.subheadline)
      .foregroundStyle(.secondary)
      .fixedSize(horizontal: false, vertical: true)
    HStack(alignment: .firstTextBaseline, spacing: 6) {
      Image(systemName: "arrow.turn.down.right")
        .foregroundStyle(.tertiary)
      Text(entry.example)
    }
    .font(.footnote)
    .foregroundStyle(.secondary)
    .padding(.top, 2)
  }
}
.padding(.vertical, 6)
.accessibilityElement(children: .combine)
.accessibilityIdentifier("whats-new-entry-\(entry.id)")
```

```kotlin
Row(
    Modifier
        .padding(vertical = 6.dp)
        .semantics(mergeDescendants = true) {}
        .testTag("whats-new-entry-${entry.id}"),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.Top,
) {
    WhatsNewIllustration(kind = entry.illustration)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.title, Modifier.listRowSeparatorLeading(), style = TextStyles.headline)
        Text(entry.summary, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SystemImage(
                "arrow.turn.down.right",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.footnote).alignBy { (it.measuredHeight * 0.8f).toInt() },
                tint = SystemColors.tertiaryLabel,
            )
            Text(entry.example, Modifier.alignByBaseline(), style = TextStyles.footnote, color = SystemColors.secondaryLabel)
        }
    }
}
```

`app/src/test/snapshots/whatsnew/WhatsNewView.light.png` and `WhatsNewView.dark.png` record the result at the iOS reference size, 440 × 956 dp at 3x. Compare them with `docs/port/reference/ios/30-whats-new-light.png` and `30-whats-new-dark.png`.
