package com.example.devicesync.core.diagnostics

object DiagnosticRedactor {
    private val forbiddenKeys = setOf(
        "content", "text", "clipboard", "filename", "filepath", "path", "uri",
        "notification", "title", "message", "secret", "token", "password", "key",
    )
    private val ipv4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val ipv6 = Regex("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f]{0,4}(?![0-9a-f:])""")
    private val windowsPath = Regex("""(?i)\b[a-z]:\\[^\s]*""")
    private val unixPath = Regex("""(?<!\w)/(?:[^/\s]+/)+[^/\s]*""")
    private val email = Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}""")
    private val longToken = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])""")

    fun sanitizeAttributes(attributes: Map<String, String>): Map<String, String> =
        attributes.entries.asSequence()
            .filterNot { (key, _) -> forbiddenKeys.any { forbidden -> key.lowercase().contains(forbidden) } }
            .take(24)
            .associate { (key, value) ->
                safeKey(key) to sanitizeValue(value)
            }

    fun sanitizeValue(value: String): String = value
        .take(512)
        .replace(windowsPath, "[PATH]")
        .replace(unixPath, "[PATH]")
        .replace(ipv4, "[IP]")
        .replace(ipv6, "[IP]")
        .replace(email, "[EMAIL]")
        .replace(longToken, "[TOKEN]")

    private fun safeKey(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' }.take(40).ifBlank { "attribute" }
}
