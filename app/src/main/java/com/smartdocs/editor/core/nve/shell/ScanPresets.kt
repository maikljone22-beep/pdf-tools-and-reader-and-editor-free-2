package com.smartdocs.editor.core.nve.shell

/**
 * User-saved scan presets (Nitro "Scan profiles": create / edit / copy / delete; built-in Good / High / Print /
 * Text-only). Values are plain strings / numbers so the model is independent of the scanner's enums.
 */
data class ScanPreset(val name: String, val paper: String, val mode: String, val quality: Int, val ocr: Boolean, val builtIn: Boolean = false) {
    fun encode(): String = listOf(name.replace("|", "/").replace("\n", " "), paper, mode, quality.toString(), if (ocr) "1" else "0").joinToString("|")

    companion object {
        /** Built-in presets (names are localized by the UI from [name] keys). */
        val BUILT_IN = listOf(
            ScanPreset("good", "A4", "COLOR", 75, false, true),
            ScanPreset("high", "A4", "COLOR", 95, false, true),
            ScanPreset("print", "FIT", "COLOR", 100, false, true),
            ScanPreset("text", "A4", "BW", 70, true, true),
        )

        fun decode(line: String): ScanPreset? {
            val p = line.split('|'); if (p.size < 5 || p[0].isBlank()) return null
            val q = p[3].toIntOrNull() ?: return null
            return ScanPreset(p[0], p[1], p[2], q.coerceIn(30, 100), p[4] == "1")
        }

        fun parse(raw: String?): List<ScanPreset> = raw.orEmpty().split('\n').mapNotNull { decode(it) }
        fun format(list: List<ScanPreset>): String = list.filter { !it.builtIn }.joinToString("\n") { it.encode() }

        /** Save [p] (replacing a user preset with the same name); built-in names cannot be overwritten. */
        fun upsert(list: List<ScanPreset>, p: ScanPreset): List<ScanPreset> =
            if (BUILT_IN.any { it.name.equals(p.name, true) }) list else list.filter { !it.name.equals(p.name, true) } + p.copy(builtIn = false)

        /** A copy named "<name> (2)" etc. */
        fun copyOf(list: List<ScanPreset>, p: ScanPreset, displayName: String): ScanPreset {
            var n = 2
            while (list.any { it.name == "$displayName ($n)" } || BUILT_IN.any { it.name == "$displayName ($n)" }) n++
            return p.copy(name = "$displayName ($n)", builtIn = false)
        }
    }
}
