# Record a view without the shared emulator

The emulator `emulator-5554` is one device. Parallel workers must not drive it. Record and check a view on the JVM with Roborazzi, then compare the PNG.

## Record

Add a test under `app/src/test/java` that renders the real composable. Use `ForcedAppearance` so light and dark do not depend on the host theme.

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h2400dp-mdpi")
class SessionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots/session",
        ),
    )

    @Test
    fun light() {
        composeRule.setContent {
            ForcedAppearance(false) {
                SystemTheme { YourScreen(Modifier.width(400.dp)) }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
```

From the worktree root:

```
./gradlew :app:recordRoborazziDebug
```

That writes `app/src/test/snapshots/<directory>/<test class>.<method>.png`. Commit the PNG with the test.

`h2400dp` is a ceiling. If the root has no fixed height, Roborazzi records the content height. Give a swipe row or a sheet a fixed height. `fillMaxSize()` inside a column consumes the remaining test height and clips whatever follows it.

Robolectric's shader compiler rejects Haze's blur shader (`'color' is not a valid layout qualifier`). Set `LocalGlassBlurEnabled` to false in a screenshot test. The PNG still shows the glass shape, tint, and rim.

## Check

```
./gradlew :app:verifyRoborazziDebug
```

A mismatch fails the task. Open the PNG. Do not treat a green test as proof if you have not looked at the image. Robolectric does not blur Haze backdrops. The glass shape, tint, and rim are in the PNG. The blur is not. Check blur on a device only when you are the only worker using `emulator-5554`.

```
android run --device emulator-5554 --activity .system.SystemCatalogActivity
android screen capture --device emulator-5554 docs/port/reference/android/system-catalog-light.png
```

Glass controls go in the `overlay` of `GlassBackdrop`. A preview that only calls `Modifier.glassEffect()` draws the tint and rim and skips the blur.
