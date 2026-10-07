@file:PortedFrom("OpenCode/SyntaxHighlighter.swift", "553a407")

package ai.opencode.mobilecode

object SyntaxHighlighter {
    fun highlight(source: String, language: String?): AttributedString = AttributedString(source)
}
