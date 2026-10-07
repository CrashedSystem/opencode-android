package ai.opencode.mobilecode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/// The iOS reference fixtures (`scripts/session-lab.py`, `approval-fixture.py`,
/// `gauntlet-fixture.py`) as session states, for the session screen's
/// screenshots and interaction tests.
object SessionViewFixture {
    val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "", password = "")
    val project = OpenCodeProject(id = "prj_lab", name = "Session lab", directory = "/fixture/approval-playground")
    val session = OpenCodeSession(
        id = "ses_approval_root",
        title = "Session rendering lab",
        projectID = project.id,
        directory = project.directory,
        updatedAt = 0,
    )
    val child = OpenCodeSession(
        id = "ses_approval_child",
        title = "UI checks",
        projectID = project.id,
        directory = project.directory,
        updatedAt = 0,
        parentID = session.id,
        agent = "general",
    )
    val context = SessionListContext(project = project, isPinned = false, isUnread = false)
    val model = OpenCodeModel(modelID = "fixture-model", providerID = "fixture", name = "Fixture model", contextLimit = 400_000)

    const val userText = "Help me improve the session screen. Show me what you find and explain the changes."
    const val prose = "I found the layout issue in `SessionView.swift`. The transcript and composer share the available height, so the keyboard is a useful stress test."
    val markdown = """## What I would change

1. Keep the composer reachable when the keyboard opens.
2. Preserve your scroll position while new text arrives.
3. Make **tool progress** easy to scan without reading every line.

---

### A small example

```swift
struct SessionSummary: Equatable {
  var title: String
  var isRunning: Bool
  var detail = "A deliberately long line to inspect horizontal scrolling on a narrow phone display"
}
```

> The fixture controls the conversation. You are looking at the actual native renderer.
"""

    fun user(id: String = "msg_lab_user", text: String = userText) = OpenCodeTranscriptItem.userText(id = id, text = text)

    fun assistant(id: String, text: String) =
        OpenCodeTranscriptItem.assistantMarkdown(id = "$id-text-0", blocks = OpenCodeMarkdownBlock.blocks(from = text))

    fun shell(status: OpenCodeToolCall.Status) = OpenCodeTranscriptItem.tool(
        id = "msg_lab_tool-tool-call_lab_test",
        call = OpenCodeToolCall(
            callID = "call_lab_test",
            messageID = "msg_lab_tool",
            name = "shell",
            status = status,
            input = OpenCodeJSONValue.`object`(
                mapOf(
                    "command" to OpenCodeJSONValue.string("swift test --filter SessionLayoutTests"),
                    "description" to OpenCodeJSONValue.string("Check session layout"),
                ),
            ),
            output = listOf(
                "Building fixture…\n✓ composer layout\n" +
                    if (status == OpenCodeToolCall.Status.completed) "✓ transcript scrolling\n2 tests passed." else "Checking transcript scrolling…",
            ),
            metadata = emptyMap(),
        ),
    )

    fun subagent() = OpenCodeTranscriptItem.tool(
        id = "msg_lab_delegate-tool-call_lab_delegate",
        call = OpenCodeToolCall(
            callID = "call_lab_delegate",
            messageID = "msg_lab_delegate",
            name = "subagent",
            status = OpenCodeToolCall.Status.running,
            input = OpenCodeJSONValue.`object`(
                mapOf(
                    "agent" to OpenCodeJSONValue.string("general"),
                    "description" to OpenCodeJSONValue.string("Check the composer and transcript layout"),
                ),
            ),
            output = emptyList(),
            metadata = mapOf("sessionID" to OpenCodeJSONValue.string(child.id)),
        ),
    )

    fun readImages(count: Int) = OpenCodeTranscriptItem.tool(
        id = "msg_lab_read_images-tool-call_lab_read",
        call = OpenCodeToolCall(
            callID = "call_lab_read",
            messageID = "msg_lab_read_images",
            name = "read",
            status = OpenCodeToolCall.Status.completed,
            input = OpenCodeJSONValue.`object`(mapOf("path" to OpenCodeJSONValue.string("fixture-images"))),
            output = listOf("Image read successfully"),
            metadata = emptyMap(),
            images = (0 until count).map { index ->
                val colors = checkerColors[index % checkerColors.size]
                OpenCodeImageAttachment(id = "lab-image-$index", data = checkerboard(colors.first, colors.second), mime = "image/png")
            },
        ),
    )

    private val checkerColors = listOf(
        0xFF5080DC.toInt() to 0xFF3562C0.toInt(),
        0xFFECA050.toInt() to 0xFFD48434.toInt(),
        0xFF285E4C.toInt() to 0xFF1C4C3C.toInt(),
        0xFF8E5CC8.toInt() to 0xFF7244AE.toInt(),
    )

    private fun checkerboard(light: Int, dark: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val cell = 40
        for (row in 0 until 6) {
            for (column in 0 until 6) {
                paint.color = if ((row + column) % 2 == 0) light else dark
                canvas.drawRect((column * cell).toFloat(), (row * cell).toFloat(), ((column + 1) * cell).toFloat(), ((row + 1) * cell).toFloat(), paint)
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun state(
        items: List<OpenCodeTranscriptItem>,
        isRunning: Boolean = false,
        session: OpenCodeSession = this.session,
    ): SessionFeature.State = SessionFeature.State(server, session, cachedItems = items, isRunning = isRunning).apply {
        models = listOf(model)
        currentModel = model.ref
        earlierHistory = SessionFeature.State.EarlierHistory.exhausted
        hasLoadedPendingRequests = true
    }

    val intro: List<OpenCodeTranscriptItem> get() = listOf(user(), assistant("msg_lab_prose", prose))
    val markdownItems: List<OpenCodeTranscriptItem> get() = intro + assistant("msg_lab_markdown", markdown)

    fun longConversation(streamUpdates: Int): List<OpenCodeTranscriptItem> {
        val items = mutableListOf<OpenCodeTranscriptItem>(user())
        for (i in 1..12) {
            val suffix = if (i == 12) (1..streamUpdates).joinToString("") { "\n\nWindow stream update %03d: the tail keeps growing while history stays anchored.".format(it) } else ""
            items += user("msg_lab_user_$i", "Review detail $i: what happens when the conversation gets longer?")
            items += assistant(
                "msg_lab_reply_$i",
                if (i == 12) suffix.trim() else "### Detail $i\n\n$prose\n\n- Keep earlier messages readable.\n- Leave the composer accessible.",
            )
        }
        return items
    }

    val approvalProject = OpenCodeProject(id = "prj_approval", name = "Approval playground", directory = "/fixture/approval-playground")
    val approvalSession = session.copy(title = "Approval playground", projectID = approvalProject.id)
    val approvalContext = SessionListContext(project = approvalProject, isPinned = false, isUnread = false)
    val approvalItems: List<OpenCodeTranscriptItem>
        get() = listOf(
            user(text = "Make questions and permissions clear and comfortable to use on iPhone."),
            assistant("msg_approval_reply", "QA is checking the native approval flow. Review the requests from UI checks when you are ready."),
        )

    val permission = OpenCodePermissionRequest(
        id = "per_approval",
        sessionID = child.id,
        action = "bash",
        resources = listOf(
            "xcodebuild test -project OpenCode.xcodeproj -scheme OpenCode -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -only-testing:OpenCodeUITests/ApprovalFlowTests -resultBundlePath /fixture/results/native-approval-flow-with-long-resource-name.xcresult",
            "/fixture/approval-playground/OpenCode/Features/Approvals/LongResourceName01.swift",
            "/fixture/approval-playground/OpenCode/Features/Approvals/LongResourceName02.swift",
            "/fixture/approval-playground/OpenCode/Features/Approvals/LongResourceName03.swift",
        ),
        save = listOf("xcodebuild test *"),
    )

    val question = OpenCodeFormRequest(
        id = "frm_approval",
        sessionID = child.id,
        title = "Question",
        fields = listOf(
            OpenCodeFormField(
                key = "approach",
                type = OpenCodeFormFieldType.string,
                description = "How should we handle approval requests?",
                options = listOf(
                    OpenCodeFormOption(value = "native", label = "Use native controls", description = "A focused sheet with clear choices and safe actions."),
                    OpenCodeFormOption(value = "dock", label = "Keep the compact dock", description = "Keep requests below the conversation."),
                ),
                custom = true,
            ),
            OpenCodeFormField(
                key = "scope",
                type = OpenCodeFormFieldType.string,
                description = "Which requests should open the sheet?",
                options = listOf(OpenCodeFormOption(value = "all", label = "All requests")),
            ),
        ),
        metadata = mapOf("kind" to OpenCodeJSONValue.string("question")),
    )

    val gauntletProject = OpenCodeProject(id = "prj_gauntlet", name = "Gauntlet api", directory = "/fixture/gauntlet-api")
    val gauntletSession = OpenCodeSession(
        id = "ses_gauntlet_09",
        title = "Polish the native feature tour",
        projectID = gauntletProject.id,
        directory = gauntletProject.directory,
        updatedAt = 0,
    )
    val gauntletChild = OpenCodeSession(
        id = "ses_gauntlet_09_child",
        title = "Review the drawer choreography",
        projectID = gauntletProject.id,
        directory = gauntletProject.directory,
        updatedAt = 0,
        parentID = gauntletSession.id,
        agent = "general",
    )
    val tourReply = """## Tour ready

The transcript stays readable while updates arrive, and every control remains native.

| Area | Result |
| --- | --- |
| Messages | Stable |
| Drawer | Responsive |"""
}
