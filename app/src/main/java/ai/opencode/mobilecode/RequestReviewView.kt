@file:PortedFrom("OpenCode/RequestReviewView.swift", "553a407")

package ai.opencode.mobilecode

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
