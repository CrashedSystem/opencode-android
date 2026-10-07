@file:PortedFrom("OpenCode/RequestReviewView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.binding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/// One live request surface. Transcript history never owns answer controls.
@Composable
fun RequestReviewView(store: Store<SessionFeature.State, SessionFeature.Action>) {
    /// Numeric answers exactly as typed, keyed by form and field. Showing the
    /// parsed value instead would reformat "1." to "1" mid-entry.
    var numberDrafts by remember { mutableStateOf(mapOf<String, String>()) }
    val focusManager = LocalFocusManager.current
    val state = store.state
    val id = state.selectedRequestID
    val form = state.formRequests.firstOrNull { it.id == id }
    val permission = state.permissionRequests.firstOrNull { it.id == id }
    val responding = id?.let { state.respondingRequestIDs.contains(it) } ?: false
    val page = id?.let { state.questionPageIndices[it] } ?: 0
    val fields = form?.activeFields(state.formAnswers[id ?: ""] ?: emptyMap()) ?: emptyList()
    val pageCount = if (form?.isQuestion == true) fields.size else 1
    val pageIndex = min(page, max(pageCount - 1, 0))
    val title = when {
        state.requestNotice != null -> "Requests"
        permission != null -> permission.title
        form != null && !form.isQuestion -> form.title
        else -> if (pageCount > 1) "Question ${pageIndex + 1} of $pageCount" else "Question"
    }
    val activePicker = store.binding(SessionFeature.State::activePicker, SessionFeature.Action::binding)
    val view = RequestReview(store, responding, focusManager::clearFocus, numberDrafts) { numberDrafts = it }
    LaunchedEffect(id) { focusManager.clearFocus() }
    LaunchedEffect(pageIndex) { focusManager.clearFocus() }

    // The medium detent shows the whole surface: the actions stay in view
    // and the request scrolls above them. Like the detent, its height does
    // not follow the request.
    val mediumDetent = (LocalConfiguration.current.screenHeightDp * 0.5f).dp - 24.dp
    NavigationStack(Modifier.height(mediumDetent).background(SystemColors.systemBackground)) {
        NavigationTitle(title, displayMode = TitleDisplayMode.inline)
        ToolbarItem(placement = ToolbarItemPlacement.topBarLeading) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(role = Role.Button) { activePicker.wrappedValue = null }
                    .semantics {
                        contentDescription = "Close"
                        testTag = "request-review-close"
                    },
                contentAlignment = Alignment.Center,
            ) {
                SystemImage("xmark", null, Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
            }
        }
        if (state.reviewRequestIDs.isNotEmpty()) {
            ToolbarItem(placement = ToolbarItemPlacement.topBarTrailing) {
                view.RequestsMenu()
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val error = state.settledRequestLoadError
            if (error != null) {
                view.ErrorView("Couldn’t refresh requests", detail = error)
                OpenCodeButton("Try Again", prominent = false) { store.send(SessionFeature.Action.refreshRequestsTapped) }
            }
            val notice = state.requestNotice
            when {
                notice != null -> view.Completion(notice)
                permission != null -> view.PermissionContent(permission)
                form != null -> if (form.isQuestion) {
                    val field = fields.getOrNull(pageIndex)
                    if (field != null) {
                        view.FormField(field, form)
                    } else {
                        Text("This request contains no questions.", style = TextStyles.body, color = SystemColors.secondaryLabel)
                    }
                } else {
                    for (field in fields) view.FormField(field, form)
                }
                else -> view.Completion("No pending requests")
            }
            val sessionID = permission?.sessionID ?: form?.sessionID
            if (notice == null && sessionID != null && sessionID != state.session?.id) {
                Text("Requested by ${view.origin(sessionID)}", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            }
        }
        view.Actions(id, form, permission, pageIndex, pageCount)
    }
}

private class RequestReview(
    val store: Store<SessionFeature.State, SessionFeature.Action>,
    val responding: Boolean,
    val clearFocus: () -> Unit,
    val numberDrafts: Map<String, String>,
    val setNumberDrafts: (Map<String, String>) -> Unit,
) {
    fun select(id: String) {
        store.send(SessionFeature.Action.reviewRequestSelected(id))
    }

    fun origin(sessionID: String): String {
        val session = store.state.familySessions.firstOrNull { it.id == sessionID }
        return session?.title ?: session?.agent ?: "Session $sessionID"
    }

    @Composable
    fun RequestsMenu() {
        var expanded by remember { mutableStateOf(false) }
        val state = store.state
        val count = state.reviewRequestIDs.size
        Box {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = !responding, role = Role.Button) { expanded = true }
                    .padding(horizontal = 10.dp)
                    .semantics { contentDescription = "Pending requests, $count" },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SystemImage("tray.full", null, Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
            }
            SystemMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                sections = listOf(
                    SystemMenuSection(
                        state.formRequests.map { request ->
                            SystemMenuItem(if (request.isQuestion) "Question · ${origin(request.sessionID)}" else request.title) {
                                expanded = false
                                select(request.id)
                            }
                        } + state.permissionRequests.map { request ->
                            SystemMenuItem("${OpenCodeToolCall.displayName(request.action)} · ${origin(request.sessionID)}") {
                                expanded = false
                                select(request.id)
                            }
                        },
                    ),
                ),
            )
        }
    }

    @Composable
    fun Completion(text: String) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SystemImage("tray", null, Modifier.symbolSize(TextStyles.headline), tint = SystemColors.label)
                Text(text, style = TextStyles.headline, color = SystemColors.label)
            }
            val next = store.state.reviewRequestIDs.firstOrNull()
            if (next != null) {
                OpenCodeButton("Review Next Request", prominent = true) { select(next) }
            } else {
                val activePicker = store.binding(SessionFeature.State::activePicker, SessionFeature.Action::binding)
                OpenCodeButton("Done", prominent = true) { activePicker.wrappedValue = null }
            }
        }
    }

    @Composable
    fun PermissionContent(request: OpenCodePermissionRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (request.resources.isEmpty()) {
                Text("No resources were supplied with this request.", style = TextStyles.body, color = SystemColors.secondaryLabel)
            }
            for (resource in request.resources) CodeText(resource)
        }
    }

    @Composable
    fun CodeText(value: String) {
        SelectionContainer {
            Text(value, Modifier.fillMaxWidth(), style = TranscriptMetrics.codeFont, color = SystemColors.label)
        }
    }

    @Composable
    fun FormField(field: OpenCodeFormField, form: OpenCodeFormRequest) {
        val answers = store.state.formAnswers[form.id] ?: emptyMap()
        val options = field.options ?: emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val question = field.description
            val fieldTitle = field.title
            when {
                form.isQuestion && !question.isNullOrEmpty() -> Text(question, style = TextStyles.headline, color = SystemColors.label)
                !fieldTitle.isNullOrEmpty() -> Text(fieldTitle, style = TextStyles.headline, color = SystemColors.label)
                field.description.isNullOrEmpty() -> Text(field.key, style = TextStyles.headline, color = SystemColors.label)
            }
            if (!form.isQuestion && question != null) {
                Text(question, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
            }
            when (field.type) {
                OpenCodeFormFieldType.boolean -> {
                    Choice("Yes", selected = answers[field.key] == OpenCodeFormValue.boolean(true)) {
                        set(form.id, field.key, OpenCodeFormValue.boolean(true))
                    }
                    Choice("No", selected = answers[field.key] == OpenCodeFormValue.boolean(false)) {
                        set(form.id, field.key, OpenCodeFormValue.boolean(false))
                    }
                }
                OpenCodeFormFieldType.external -> {
                    val url = field.url
                    if (url != null) {
                        val uriHandler = LocalUriHandler.current
                        OpenCodeButton("Open Step", prominent = false) { uriHandler.openUri(url) }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Completed", Modifier.weight(1f), style = TextStyles.body, color = SystemColors.label)
                        Switch(
                            checked = answers[field.key] == OpenCodeFormValue.boolean(true),
                            onCheckedChange = { set(form.id, field.key, OpenCodeFormValue.boolean(it)) },
                            enabled = !responding,
                            colors = SwitchDefaults.colors(checkedTrackColor = SystemColors.systemGreen),
                        )
                    }
                }
                OpenCodeFormFieldType.multiselect -> {
                    Text("Choose one or more", style = TextStyles.caption, color = SystemColors.secondaryLabel)
                    for (option in options) {
                        Choice(option.label, description = option.description, selected = selectedValues(answers[field.key]).contains(option.value), multiple = true) {
                            val values = selectedValues(store.state.formAnswers[form.id]?.get(field.key)).toMutableList()
                            if (!values.remove(option.value)) values += option.value
                            set(form.id, field.key, OpenCodeFormValue.strings(values))
                        }
                    }
                    if (field.custom == true) {
                        val known = options.map { it.value }.toSet()
                        AnswerField(
                            hasOptions = options.isNotEmpty(),
                            text = selectedValues(answers[field.key]).filter { !known.contains(it) }.joinToString(", "),
                            onText = { text ->
                                val values = selectedValues(store.state.formAnswers[form.id]?.get(field.key)).filter(known::contains).toMutableList()
                                if (text.isNotEmpty()) values += text
                                set(form.id, field.key, OpenCodeFormValue.strings(values))
                            },
                        )
                    }
                }
                OpenCodeFormFieldType.string, OpenCodeFormFieldType.number, OpenCodeFormFieldType.integer -> {
                    for (option in options) {
                        Choice(option.label, description = option.description, selected = answers[field.key] == OpenCodeFormValue.string(option.value)) {
                            set(form.id, field.key, OpenCodeFormValue.string(option.value))
                        }
                    }
                    if (options.isEmpty() || field.custom == true) {
                        val known = options.map { it.value }.toSet()
                        val draftKey = "${form.id}/${field.key}"
                        val text = when (val value = answers[field.key]) {
                            is OpenCodeFormValue.string -> if (known.contains(value.value)) "" else value.value
                            is OpenCodeFormValue.number -> numberDrafts[draftKey] ?: java.text.NumberFormat.getInstance().format(value.value)
                            else -> numberDrafts[draftKey] ?: ""
                        }
                        AnswerField(
                            hasOptions = options.isNotEmpty(),
                            text = text,
                            keyboardType = if (field.type == OpenCodeFormFieldType.string) KeyboardType.Text else KeyboardType.Decimal,
                            onText = { typed ->
                                if (field.type == OpenCodeFormFieldType.string) {
                                    set(form.id, field.key, OpenCodeFormValue.string(typed))
                                } else {
                                    setNumberDrafts(numberDrafts + (draftKey to typed))
                                    set(form.id, field.key, OpenCodeFormValue.typedNumber(typed))
                                }
                            },
                        )
                    }
                }
            }
            val value = answers[field.key]
            val error = value?.let { field.validationError(it) }
            if (error != null) Text(error, style = TextStyles.footnote, color = SystemColors.systemRed)
        }
    }

    fun selectedValues(value: OpenCodeFormValue?): List<String> = (value as? OpenCodeFormValue.strings)?.value ?: emptyList()

    fun set(id: String, key: String, value: OpenCodeFormValue?) {
        store.send(SessionFeature.Action.formValueChanged(formID = id, key = key, value = value))
    }

    @Composable
    fun Choice(
        title: String,
        description: String? = null,
        selected: Boolean,
        multiple: Boolean = false,
        action: () -> Unit,
    ) {
        val shape = RoundedCornerShape(TranscriptMetrics.controlCornerRadius.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(SystemColors.secondarySystemBackground)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) SystemColors.tint else SystemColors.separator, shape)
                .clickable(enabled = !responding, role = Role.Button, onClick = action)
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = title
                    stateDescription = if (selected) "Selected" else "Not selected"
                    this.selected = selected
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SystemImage(
                if (selected) (if (multiple) "checkmark.square.fill" else "checkmark.circle.fill") else (if (multiple) "square" else "circle"),
                null,
                Modifier.symbolSize(TextStyles.body),
                tint = if (selected) SystemColors.tint else SystemColors.secondaryLabel,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = TextStyles.body.medium, color = SystemColors.label)
                if (!description.isNullOrEmpty()) {
                    Text(description, style = TextStyles.footnote, color = SystemColors.secondaryLabel)
                }
            }
        }
    }

    @Composable
    fun AnswerField(
        hasOptions: Boolean,
        text: String,
        onText: (String) -> Unit,
        keyboardType: KeyboardType = KeyboardType.Text,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(SystemColors.secondarySystemBackground, RoundedCornerShape(TranscriptMetrics.controlCornerRadius.dp))
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(if (hasOptions) "Or write your own answer" else "Your answer", style = TextStyles.body, color = SystemColors.placeholderText)
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                enabled = !responding,
                textStyle = TextStyles.body.copy(color = SystemColors.label),
                cursorBrush = SolidColor(SystemColors.tint),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your answer" },
            )
        }
    }

    @Composable
    fun ErrorView(title: String, detail: String) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SystemImage("exclamationmark.triangle.fill", null, Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.systemRed)
                Text(title, style = TextStyles.subheadline.semibold, color = SystemColors.systemRed)
            }
            Text(detail, style = TextStyles.footnote, color = SystemColors.systemRed)
        }
    }

    @Composable
    fun Actions(
        id: String?,
        form: OpenCodeFormRequest?,
        permission: OpenCodePermissionRequest?,
        pageIndex: Int,
        pageCount: Int,
    ) {
        if (store.state.requestNotice != null || id == null || (form == null && permission == null)) return
        Column(
            Modifier
                .fillMaxWidth()
                .background(SystemColors.systemBackground)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val error = store.state.requestErrors[id]
            if (error != null) Box(Modifier.fillMaxWidth()) { ErrorView("Response not confirmed", detail = error) }
            val style = TextStyles.subheadline.medium
            when {
                responding -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
                    Text("Sending response…", style = style, color = SystemColors.label)
                }
                permission != null -> {
                    // Denying is a normal answer, not a destructive action: neutral
                    // peers above the one prominent choice, matching question pages.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OpenCodeButton("Deny", prominent = false, modifier = Modifier.weight(1f), style = style) {
                            store.send(SessionFeature.Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.reject))
                        }
                        if (permission.save?.isNotEmpty() == true) {
                            OpenCodeButton("Always Allow", prominent = false, modifier = Modifier.weight(1f), style = style) {
                                store.send(SessionFeature.Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.always))
                            }
                        }
                    }
                    OpenCodeButton("Allow Once", prominent = true, modifier = Modifier.fillMaxWidth(), style = style) {
                        store.send(SessionFeature.Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.once))
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pageIndex > 0) {
                        OpenCodeButton("Back", prominent = false, style = style) {
                            store.send(SessionFeature.Action.questionPageChanged(requestID = id, index = pageIndex - 1))
                        }
                    }
                    OpenCodeButton(
                        if (form?.isQuestion == false) "Cancel" else "Skip",
                        prominent = false,
                        style = style,
                        modifier = Modifier.semantics { contentDescription = if (form?.isQuestion == false) "Cancel request" else "Skip request" },
                    ) {
                        clearFocus()
                        store.send(SessionFeature.Action.formCancelTapped(id))
                    }
                    Spacer(Modifier.weight(1f))
                    if (pageIndex + 1 < pageCount) {
                        OpenCodeButton("Next", prominent = true, style = style) {
                            store.send(SessionFeature.Action.questionPageChanged(requestID = id, index = pageIndex + 1))
                        }
                    } else {
                        OpenCodeButton("Send Answers", prominent = true, style = style, enabled = pageCount != 0) {
                            clearFocus()
                            store.send(SessionFeature.Action.formSubmitTapped(id))
                        }
                    }
                }
            }
        }
    }
}

/// The sheet title asks what the agent wants to do, not just “Permission”.
val OpenCodePermissionRequest.title: String
    get() {
        val count = resources.size
        return when (action) {
            "shell", "bash" -> "Run Command?"
            "edit", "write", "patch" -> if (count > 1) "Edit $count Files?" else "Edit File?"
            "read" -> if (count > 1) "Read $count Files?" else "Read File?"
            "external_directory" -> "Access a Folder Outside the Project?"
            "webfetch" -> "Fetch a Web Page?"
            "websearch" -> "Search the Web?"
            "grep", "glob", "list" -> "Search Files?"
            "subagent", "task" -> "Start a Subagent?"
            else -> "Allow ${OpenCodeToolCall.displayName(action)}?"
        }
    }
