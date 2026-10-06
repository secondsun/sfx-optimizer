package dev.secondsun.sfxoptimizer.viewer

data class LocationDto(
    val line: Int,
    val colStart: Int,
    val colEnd: Int,
)

data class SourceTokenDto(
    val text: String,
    val type: String,
    val cssClass: String,
    val line: Int,
    val colStart: Int,
    val colEnd: Int,
    val variableKey: String? = null,
    val functionScope: String? = null,
    val isWrite: Boolean = false,
    val isRead: Boolean = false,
    val implicitReads: List<String> = emptyList(),
    val implicitWrites: List<String> = emptyList(),
)

data class SourceLineDto(
    val lineNumber: Int,
    val text: String,
    val tokens: List<SourceTokenDto>,
)

data class IntervalSpanDto(
    val keyName: String,
    val functionScope: String? = null,
    val isRegister: Boolean,
    val registerName: String?,
    val startLine: Int,
    val endLine: Int,
    val reads: List<LocationDto>,
    val writes: List<LocationDto>,
    val interferences: List<String>,
    val allocatedRegister: String?,
    val isSpill: Boolean,
)

data class CodeBlockDto(
    val id: String,
    val startLine: Int,
    val endLine: Int,
    val entrances: List<String>,
    val exits: List<String>,
    val intervals: List<IntervalSpanDto>,
)

data class FunctionScopeDto(
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val intervals: List<IntervalSpanDto>,
    val allocatedMap: Map<String, String>,
)

data class FileAnalysisResultDto(
    val fileName: String,
    val totalLines: Int,
    val lines: List<SourceLineDto>,
    val blocks: List<CodeBlockDto>,
    val intervals: List<IntervalSpanDto>,
    val allocatedMap: Map<String, String>,
    val functions: List<FunctionScopeDto> = emptyList(),
)
