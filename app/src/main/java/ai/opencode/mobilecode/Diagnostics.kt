@file:PortedFrom("OpenCode/Diagnostics.swift", "553a407", partial = "LabMarks hooks: isEnabled, recordMark, networkMetrics")

package ai.opencode.mobilecode

/// Stand-in for the fields `LabMarks.networkTask` reads off `URLSessionTaskMetrics`.
/// The diagnostics porter replaces this file.
data class URLSessionTaskMetrics(
    val transactionMetrics: List<URLSessionTaskTransactionMetrics>,
)

data class URLSessionTaskTransactionMetrics(
    val isReusedConnection: Boolean,
    val networkProtocolName: String? = null,
    val connectStartDate: Long? = null,
    val connectEndDate: Long? = null,
    val secureConnectionStartDate: Long? = null,
    val secureConnectionEndDate: Long? = null,
)

class DiagnosticsRecorder {
    val isEnabled: Boolean = false

    fun recordMark(name: String, sessionID: String?) = Unit

    fun networkMetrics(requestID: String, path: String, metrics: URLSessionTaskMetrics) = Unit

    companion object {
        val shared = DiagnosticsRecorder()
    }
}
