import XCTest

/// Drives the installed OpenCode app against the iOS repo's loopback fixtures
/// and keeps a full-screen screenshot of each named state.
@MainActor
final class CaptureTests: XCTestCase {
  private let app = XCUIApplication(bundleIdentifier: "ai.opencode.mobilecode")
  private let sessionLab = "http://127.0.0.1:4110"
  private let gauntlet = "http://127.0.0.1:4120"
  private let approval = "http://127.0.0.1:4105"
  private let labSession = "ses_approval_root"

  private var appearance: String {
    let env = ProcessInfo.processInfo.environment["CAPTURE_APPEARANCE"]
      ?? ProcessInfo.processInfo.environment["TEST_RUNNER_CAPTURE_APPEARANCE"]
    if env == "dark" || env == "light" { return env! }
    if let text = try? String(contentsOfFile: "/private/tmp/opencode-capture-appearance", encoding: .utf8) {
      let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
      if value == "dark" || value == "light" { return value }
    }
    return "light"
  }

  override func setUp() {
    continueAfterFailure = false
    app.terminate()
  }

  func testConnectScreens() async throws {
    launch(server: nil)
    XCTAssertTrue(app.buttons["Scan Pairing Code"].waitForExistence(timeout: 20))
    try await settle()
    shot("01-first-launch")

    tap(app.buttons["Enter a Server Manually"])
    XCTAssertTrue(app.textFields["Server address"].waitForExistence(timeout: 8))
    try await settle()
    shot("02-server-manual")

    let address = app.textFields["Server address"]
    address.tap()
    address.typeText("http://127.0.0.1:9")
    tap(app.buttons["Connect"])
    XCTAssertTrue(app.buttons["Copy Error Report"].waitForExistence(timeout: 20))
    try await settle()
    shot("03-connect-error")
  }

  func testDrawerCatchUpAndWhatsNew() async throws {
    try await post(gauntlet, "/lab/act", ["action": "reset"])
    try await post(gauntlet, "/lab/act", ["action": "start", "sessionID": "ses_gauntlet_04"])
    try await post(gauntlet, "/lab/act", ["action": "ask", "sessionID": "ses_gauntlet_05"])
    launch(server: gauntlet, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_WHATS_NEW": "unseen",
      "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    ])
    try await openDrawer()
    // Pins persist on the simulator. Long-pressing an already-pinned row opens
    // Unpin, and XCTest then waits out a context-menu animation that never completes.
    for id in ["ses_gauntlet_02", "ses_gauntlet_03"] {
      let row = app.buttons["drawer-session-row-\(id)"]
      XCTAssertTrue(row.waitForExistence(timeout: 8))
      if (row.value as? String)?.contains("Pinned") == true { continue }
      row.press(forDuration: 0.8)
      let pin = app.buttons["Pin"]
      XCTAssertTrue(pin.waitForExistence(timeout: 3))
      pin.tap()
      try await waitUntil(timeout: 4) {
        (row.value as? String)?.contains("Pinned") == true
          && !self.app.buttons["Pin"].exists
          && !self.app.buttons["Unpin"].exists
      }
    }
    XCTAssertFalse(app.buttons["Unpin"].exists)
    XCTAssertFalse(app.buttons["Pin"].exists)
    XCTAssertTrue(app.buttons["drawer-catch-up"].waitForExistence(timeout: 8))
    XCTAssertTrue(app.buttons["drawer-whats-new"].waitForExistence(timeout: 8))
    try await settle()
    shot("04-drawer")

    tap(app.buttons["drawer-catch-up"])
    XCTAssertTrue(app.buttons["catch-up-done"].waitForExistence(timeout: 8))
    try await settle()
    shot("29-catch-up")
    app.terminate()

    launch(server: gauntlet, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_WHATS_NEW": "unseen",
    ])
    try await openDrawer()
    let search = app.textFields["Search sessions"]
    search.tap()
    search.typeText("composer")
    XCTAssertTrue(app.buttons["drawer-session-row-ses_gauntlet_02"].waitForExistence(timeout: 5))
    try await settle()
    shot("05-drawer-search")
    app.terminate()

    launch(server: gauntlet, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_WHATS_NEW": "unseen",
    ])
    try await openDrawer()
    tap(app.buttons["drawer-whats-new"])
    XCTAssertTrue(app.descendants(matching: .any)["whats-new-list"].waitForExistence(timeout: 8))
    try await settle()
    shot("30-whats-new")
  }

  func testDraftComposerAndMenus() async throws {
    try await select("intro")
    launch(server: sessionLab, extra: labSessionEnvironment())
    XCTAssertTrue(app.buttons["Open sessions"].waitForExistence(timeout: 20))
    try await openDrawer()
    tap(app.buttons["New Session"])
    XCTAssertTrue(app.buttons["Project"].waitForExistence(timeout: 8))
    try await settle()
    shot("06-draft")

    try await openDrawer()
    tap(app.buttons["drawer-session-row-\(labSession)"])
    XCTAssertTrue(field("Message").waitForExistence(timeout: 8))
    try await settle()
    shot("35-composer-empty")

    let message = field("Message")
    message.tap()
    message.typeText("Check the composer with a short note")
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
    try await settle()
    shot("17-composer-text")
    app.terminate()

    try await select("intro")
    launch(server: sessionLab, extra: labSessionEnvironment(), arguments: ["-lab-pasteboard-image"])
    XCTAssertTrue(field("Message").waitForExistence(timeout: 20))
    tap(app.buttons["Add attachment"])
    // Photos is in every menu; Camera and Paste depend on the device.
    XCTAssertTrue(app.buttons["Photos"].waitForExistence(timeout: 5))
    try await settle()
    shot("26-attachment-menu")
    if app.buttons["Paste"].waitForExistence(timeout: 3) {
      try await Task.sleep(for: .milliseconds(500))
      tap(app.buttons["Paste"])
      XCTAssertTrue(app.descendants(matching: .any)["pending-attachment-0"].waitForExistence(timeout: 6))
      try await settle()
      shot("18-composer-attachments")
    }

    messageFocusAndOpenModel()
    XCTAssertTrue(app.navigationBars["Model"].waitForExistence(timeout: 8))
    try await settle()
    shot("25-model-picker")
  }

  func testTranscript() async throws {
    try await select("typography")
    try await openLabSession()
    XCTAssertTrue(text("The Effect upgrade, change by change").waitForExistence(timeout: 20))
    try await settle()
    shot("09-transcript-table")
    try await reveal("the core tool runtime")
    shot("08-transcript-tasks")
    try await parkHeading("The Effect upgrade, change by change")
    shot("07-transcript-markdown")
    app.terminate()

    try await select("files")
    try await openLabSession()
    XCTAssertTrue(text("recorded the drawer").waitForExistence(timeout: 20))
    try await settle()
    shot("10-transcript-links")
    app.terminate()

    try await select("tool-completed")
    try await openLabSession()
    let used = app.buttons["Used 1 Shell"]
    XCTAssertTrue(used.waitForExistence(timeout: 20))
    try await settle()
    shot("11-tools-collapsed")
    used.tap()
    XCTAssertTrue(text("swift test --filter SessionLayoutTests").waitForExistence(timeout: 5) || text("2 tests passed").waitForExistence(timeout: 5))
    try await settle()
    shot("12-tools-expanded")
    app.terminate()

    try await select("subagent")
    try await openLabSession()
    let subagent = app.buttons["Used 1 Subagent"]
    XCTAssertTrue(subagent.waitForExistence(timeout: 20))
    subagent.tap()
    XCTAssertTrue(app.buttons["subagent-task"].waitForExistence(timeout: 6))
    try await settle()
    shot("13-subagent")
    app.terminate()

    try await select("tool-images")
    try await openLabSession()
    XCTAssertTrue(app.descendants(matching: .any)["tool-image-mosaic"].waitForExistence(timeout: 20))
    try await settle()
    shot("14-images")
    app.terminate()

    try await select("tool-running")
    try await openLabSession()
    XCTAssertTrue(app.staticTexts["Working"].waitForExistence(timeout: 20))
    try await settle()
    shot("15-running")

    let message = field("Message")
    message.tap()
    message.typeText("Steer this")
    let send = app.buttons["Steer message"]
    XCTAssertTrue(send.waitForExistence(timeout: 5))
    send.press(forDuration: 0.9)
    if !app.buttons["Queue"].waitForExistence(timeout: 2) { send.press(forDuration: 1.1) }
    XCTAssertTrue(app.buttons["Steer"].waitForExistence(timeout: 4))
    try await Task.sleep(for: .milliseconds(600))
    shot("19-composer-steer-menu")
    app.terminate()

    try await select("long")
    try await openLabSession()
    try await post(sessionLab, "/lab/window-stream", ["durationMs": 6000, "intervalMs": 200])
    XCTAssertTrue(text("Window stream update").waitForExistence(timeout: 8))
    try await settle()
    shot("16-streaming")
  }

  func testRequestsHeaderAndSheet() async throws {
    try await post(approval, "/fixture/reset", ["mode": "permission", "emitEvent": true])
    try await openFixtureSession(approval, id: labSession)
    try await openReview()
    XCTAssertTrue(app.buttons["Allow Once"].waitForExistence(timeout: 8))
    try await settle()
    shot("20-permission")
    app.terminate()

    try await post(approval, "/fixture/reset", ["mode": "question", "emitEvent": true])
    try await openFixtureSession(approval, id: labSession)
    try await openReview()
    XCTAssertTrue(app.buttons["Use native controls"].waitForExistence(timeout: 8))
    try await settle()
    shot("21-question")
    app.terminate()

    try await select("markdown")
    try await post(sessionLab, "/lab/context", ["percent": 85, "cost": 1.42])
    try await openLabSession()
    let menu = app.buttons["session-menu"]
    XCTAssertTrue(menu.waitForExistence(timeout: 20))
    try await waitUntil(timeout: 12) { (menu.value as? String)?.contains("85%") == true }
    try await settle()
    shot("22-header-context")
    menu.tap()
    XCTAssertTrue(app.buttons["Session Info"].waitForExistence(timeout: 5))
    try await settle()
    shot("23-session-menu")
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()

    tap(app.buttons["session-title"])
    XCTAssertTrue(app.descendants(matching: .any)["session-sheet"].waitForExistence(timeout: 6))
    try await settle()
    shot("24-session-sheet")
  }

  func testBackgroundShellSettingsAndError() async throws {
    try await post(gauntlet, "/lab/act", ["action": "reset"])
    try await post(gauntlet, "/lab/act", ["action": "shell-start", "sessionID": "ses_gauntlet_09", "text": "bun dev --port 3000", "bytes": 3000])
    try await post(gauntlet, "/lab/act", ["action": "tour-child-start", "sessionID": "ses_gauntlet_09"])
    launch(server: gauntlet, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    ])
    try await openDrawer()
    tap(app.buttons["drawer-session-row-ses_gauntlet_09"])
    let capsule = app.buttons["background-activity"]
    XCTAssertTrue(capsule.waitForExistence(timeout: 20))
    try await waitUntil(timeout: 8) { capsule.label.contains("shell") }
    capsule.tap()
    XCTAssertTrue(app.descendants(matching: .any)["background-work-panel"].waitForExistence(timeout: 5))
    try await settle()
    shot("27-background-work")
    tap(app.buttons["background-shell-sh_gauntlet_1"])
    XCTAssertTrue(app.descendants(matching: .any)["shell-output"].waitForExistence(timeout: 6))
    try await settle()
    shot("28-shell-output")
  }

  func testSettingsDiagnosticsAndError() async throws {
    try await select("markdown")
    launch(server: sessionLab, extra: labSessionEnvironment())
    try await openDrawer()
    tap(app.buttons["Settings"])
    XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 6))
    try await settle()
    shot("31-settings")
    let diagnostics = app.staticTexts["Diagnostics"]
    if !diagnostics.isHittable { app.swipeUp() }
    tap(diagnostics)
    XCTAssertTrue(app.navigationBars["Diagnostics"].waitForExistence(timeout: 6))
    try await settle()
    shot("32-diagnostics")
    app.terminate()

    try await post(approval, "/fixture/reset", ["mode": "initial-fetch-failure", "emitEvent": true])
    launch(server: approval, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    ])
    try await openDrawer()
    tap(app.buttons["drawer-session-row-ses_approval_root"])
    XCTAssertTrue(app.buttons["request-load-error"].waitForExistence(timeout: 20))
    try await settle()
    shot("33-error-banner")
  }

  // MARK: Launch

  private func launch(server: String?, extra: [String: String] = [:], arguments: [String] = []) {
    app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-lab-interface-style", appearance] + arguments
    var environment = ["OPENCODE_PASSWORD": ""]
    if let server {
      environment["OPENCODE_LAB_SERVER_URL"] = server
    }
    for (key, value) in extra { environment[key] = value }
    app.launchEnvironment = environment
    app.launch()
  }

  private func labSessionEnvironment() -> [String: String] {
    [
      "OPENCODE_LAB_LAST_SESSION_ID": labSession,
      "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    ]
  }

  private func openLabSession() async throws {
    try await openFixtureSession(sessionLab, id: labSession)
  }

  private func openFixtureSession(_ server: String, id: String) async throws {
    launch(server: server, extra: [
      "OPENCODE_LAB_LAST_SESSION_ID": "-",
      "OPENCODE_LAB_DISABLE_TRANSCRIPT_DISK_CACHE": "1",
    ])
    try await openDrawer()
    tap(app.buttons["drawer-session-row-\(id)"])
    XCTAssertTrue(app.buttons["session-menu"].waitForExistence(timeout: 12))
  }

  private func openDrawer() async throws {
    let open = app.buttons["Open sessions"]
    XCTAssertTrue(open.waitForExistence(timeout: 20))
    if !app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "drawer-session-row-")).firstMatch.isHittable {
      open.tap()
    }
    try await waitUntil(timeout: 12) {
      self.app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "drawer-session-row-")).firstMatch.isHittable
    }
  }

  private func openReview() async throws {
    let review = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR identifier == %@", "Review", "Review requests")).firstMatch
    try await waitUntil(timeout: 15) {
      review.exists || self.app.buttons["request-review-close"].exists || self.app.buttons["Allow Once"].exists
    }
    if app.buttons["Allow Once"].exists || app.buttons["Use native controls"].exists { return }
    if !app.buttons["request-review-close"].exists { review.tap() }
    XCTAssertTrue(app.buttons["request-review-close"].waitForExistence(timeout: 8))
  }

  private func messageFocusAndOpenModel() {
    let message = field("Message")
    if message.exists { message.tap() }
    let choose = app.buttons["Choose model"]
    if choose.waitForExistence(timeout: 4) { choose.tap() }
  }

  /// The open transcript follows the bottom. Short drags stop on the row instead of jumping past it.
  private func reveal(_ fragment: String) async throws {
    let target = text(fragment)
    let heading = text("The Effect upgrade, change by change")
    let list = app.collectionViews["transcript-list"]
    for _ in 0..<24 {
      if inBand(target) { return }
      nudge(list, dy: drag(for: target, heading: heading))
      try await Task.sleep(for: .milliseconds(280))
    }
    XCTAssertTrue(inBand(target), fragment)
  }

  private func parkHeading(_ fragment: String) async throws {
    let heading = text(fragment)
    let list = app.collectionViews["transcript-list"]
    for _ in 0..<16 {
      if heading.exists, heading.frame.minY > 100, heading.frame.minY < 220 { return }
      let dy: CGFloat = heading.exists && heading.frame.minY > 220 ? -0.14 : 0.14
      nudge(list, dy: dy)
      try await Task.sleep(for: .milliseconds(280))
    }
    XCTAssertTrue(heading.exists)
  }

  private func drag(for target: XCUIElement, heading: XCUIElement) -> CGFloat {
    if target.exists, target.frame.height > 1 {
      return target.frame.midY < 220 ? 0.12 : -0.12
    }
    if heading.exists, heading.frame.minY < 500 { return -0.14 }
    return 0.14
  }

  private func nudge(_ list: XCUIElement, dy: CGFloat) {
    let host = list.exists ? list : app
    let startY = dy > 0 ? 0.40 : 0.64
    let start = host.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: startY))
    let end = host.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: startY + dy))
    start.press(forDuration: 0.05, thenDragTo: end, withVelocity: 180, thenHoldForDuration: 0.15)
  }

  private func inBand(_ element: XCUIElement) -> Bool {
    guard element.exists, element.frame.height > 1 else { return false }
    return element.frame.minY > 160 && element.frame.maxY < app.frame.height - 170
  }

  // MARK: Fixture

  private func select(_ checkpoint: String) async throws {
    try await post(sessionLab, "/lab/select", ["checkpoint": checkpoint])
  }

  private func post(_ base: String, _ path: String, _ body: [String: Any]) async throws {
    var components = try XCTUnwrap(URLComponents(string: base))
    components.path = path
    var request = URLRequest(url: try XCTUnwrap(components.url))
    request.httpMethod = "POST"
    request.timeoutInterval = 8
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    let (_, response) = try await URLSession.shared.data(for: request)
    XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, "\(base)\(path)")
  }

  // MARK: Elements

  private func field(_ label: String) -> XCUIElement {
    let field = app.textFields[label]
    return field.exists ? field : app.textViews[label]
  }

  private func text(_ fragment: String) -> XCUIElement {
    app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
  }

  private func tap(_ element: XCUIElement) {
    XCTAssertTrue(element.waitForExistence(timeout: 8))
    element.tap()
  }

  private func shot(_ stem: String) {
    let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    attachment.name = "\(stem)-\(appearance)"
    attachment.lifetime = .keepAlways
    add(attachment)
    let image = XCUIScreen.main.screenshot().image
    let note = XCTAttachment(string: "\(image.size.width)x\(image.size.height)@\(image.scale)")
    note.name = "geometry"
    note.lifetime = .keepAlways
    add(note)
  }

  private func settle() async throws {
    try await Task.sleep(for: .milliseconds(700))
  }

  private func waitUntil(timeout: TimeInterval = 8, _ condition: @escaping () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      if condition() { return }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(condition())
  }
}
