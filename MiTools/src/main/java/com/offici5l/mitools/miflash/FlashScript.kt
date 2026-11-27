package com.offici5l.mitools.miflash

sealed class Cond {

    class Getvar(val variable: String, val pattern: Regex) : Cond()

    class CommandOutput(val command: String, val pattern: Regex) : Cond()

    class Anti(val op: String) : Cond()
}

sealed class Step {
    class Flash(val partition: String, val file: String, val slotAll: Boolean) : Step()
    class Erase(val partition: String) : Step()
    class SetActive(val slot: String) : Step()

    class Reboot(val target: String?) : Step()

    class Raw(val command: String) : Step()

    class Require(val cond: Cond, val message: String) : Step()

    class If(val cond: Cond, val matchExpected: Boolean, val then: List<Step>, val otherwise: List<Step>) : Step()
}

class ParsedScript(
    val steps: List<Step>,

    val antiVersion: Int?,

    val hasAntiCheck: Boolean,

    val hasSecurityPatchCheck: Boolean,
    val unsupported: List<String>,
)

object FlashScript {

    private val ANTI = Regex("CURRENT_ANTI_VER\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val BACKTICKS = Regex("`[^`]*`")
    private val SUBSHELL = Regex("\\\$\\((?:[^()]|\\([^()]*\\))*\\)")
    private val SPLIT = Regex("\\|\\||&&|\\||;")
    private val REDIRECT = Regex("\\s*(2>&1|\\d?>).*")
    private val HOST_ARG = Regex("^(\\\$\\*|%\\*|\\\$@|\"\\\$@\")$")
    private val IF_SINGLE_LINE = Regex("(^|[;\\s])fi\\s*;?\\s*$")
    private val Q_EQ_0 = Regex("\\\$\\?\\s*-eq\\s*0")
    private val Q_NE_0 = Regex("\\\$\\?\\s*-ne\\s*0")
    private val ECHO_MSG = Regex("echo\\s+\"([^\"]*)\"")

    private val VAR_FROM_GETVAR = Regex("^(\\w+)=(?:`|\\\$\\()\\s*fastboot\\b[^`)]*?\\bgetvar\\s+([A-Za-z0-9_:.\\-]+)")
    private val SUPPORTED_TYPES = Regex("SUPPORTED_TYPES=\\(([^)]*)\\)")
    private val QUOTED = Regex("\"([^\"]*)\"")

    private val ANTI_COMPARE = Regex("\\[\\s*\\\$\\{?(\\w+)\\}?\\s+-(eq|ne|gt|lt|ge|le)\\s+\\\$\\{?CURRENT_ANTI_VER\\}?\\s*\\]")

    private val VAR_EQUALS = Regex("\\[+\\s*\"?\\\$\\{?(\\w+)\\}?\"?\\s*(==|=|!=)\\s*\"?([^\"\\s\\]]+)\"?\\s*\\]+")

    private val TYPE_IN_LIST = Regex("\\\$\\{(\\w+)\\}")

    private fun clean(line: String): String =
        line.replace(BACKTICKS, "").replace(SUBSHELL, "").replace("%~dp0", "")

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var has = false
        for (c in s) {
            if (quote != null) {
                if (c == quote) quote = null else sb.append(c)
            } else if (c == '"' || c == '\'') {
                quote = c
                has = true
            } else if (c.isWhitespace()) {
                if (has || sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.clear()
                    has = false
                }
            } else {
                sb.append(c)
            }
        }
        if (has || sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private class Frame(val cond: Cond?, val matchExpected: Boolean, val chained: Boolean) {
        val then = ArrayList<Step>()
        val otherwise = ArrayList<Step>()
        var inElse = false
        fun target(): MutableList<Step> = if (inElse) otherwise else then
    }

    fun parse(text: String): ParsedScript {
        val root = ArrayList<Step>()
        val stack = ArrayList<Frame>()
        val unsupported = ArrayList<String>()
        val vars = HashMap<String, String>()
        var supportedTypes = listOf("raw", "ext4")
        var anti: Int? = null
        var pending: Cond? = null

        fun out(): MutableList<Step> = if (stack.isEmpty()) root else stack[stack.size - 1].target()

        fun headerCondition(line: String, low: String, fromGetvarPipe: Cond?): Pair<Cond, Boolean>? {
            val isEq = Q_EQ_0.containsMatchIn(low)
            val isNe = Q_NE_0.containsMatchIn(low)
            if (fromGetvarPipe != null && (isEq || isNe)) return Pair(fromGetvarPipe, isEq)

            ANTI_COMPARE.find(line)?.let { m ->
                if (vars[m.groupValues[1]] == "anti") return Pair(Cond.Anti(m.groupValues[2]), true)
            }
            if (line.contains("SUPPORTED_TYPES") && line.contains("-n")) {
                val v = TYPE_IN_LIST.find(line)?.groupValues?.get(1)
                val name = if (v != null) vars[v] else null
                if (name != null) {
                    val alternatives = supportedTypes.joinToString("|") { Regex.escape(it) }
                    return Pair(Cond.Getvar(name, Regex("^" + Regex.escape(name) + ":\\s*(" + alternatives + ")\\s*$")), true)
                }
            }
            VAR_EQUALS.find(line)?.let { m ->
                val name = vars[m.groupValues[1]]
                if (name != null) {
                    val re = Regex("^" + Regex.escape(name) + ":\\s*" + Regex.escape(m.groupValues[3]) + "\\s*$")
                    return Pair(Cond.Getvar(name, re), m.groupValues[2] != "!=")
                }
            }
            return null
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("::")) continue
            val low = line.lowercase()
            if (low.startsWith("rem ")) continue

            ANTI.find(line)?.let { anti = it.groupValues[1].toIntOrNull() }

            VAR_FROM_GETVAR.find(line)?.let { vars[it.groupValues[1]] = it.groupValues[2] }
            SUPPORTED_TYPES.find(line)?.let { m ->
                supportedTypes = QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            }

            if (low.startsWith("if ") || low.startsWith("if[")) {
                val cond = pending
                pending = null
                if (low.contains("then") && IF_SINGLE_LINE.containsMatchIn(low)) {

                    if (cond != null && Q_NE_0.containsMatchIn(low) && low.contains("exit")) {
                        out().add(Step.Require(cond, ECHO_MSG.find(line)?.groupValues?.get(1) ?: "A device check failed."))
                    }
                } else {
                    val h = headerCondition(line, low, cond)
                    stack.add(if (h != null) Frame(h.first, h.second, false) else Frame(null, true, false))
                }
                continue
            }
            if (low == "then") continue
            if (low == "else" || low.startsWith("else ")) {
                if (stack.isNotEmpty()) stack[stack.size - 1].inElse = true
                continue
            }
            if (low.startsWith("elif")) {
                if (stack.isNotEmpty()) {
                    stack[stack.size - 1].inElse = true
                    val h = headerCondition(line, low, null)
                    stack.add(if (h != null) Frame(h.first, h.second, true) else Frame(null, true, true))
                } else {
                    unsupported.add(line)
                }
                continue
            }
            if (low == "fi" || low == "fi;" || low.startsWith("fi ")) {

                while (stack.isNotEmpty()) {
                    val f = stack.removeAt(stack.size - 1)
                    val c = f.cond
                    if (c != null) {
                        out().add(Step.If(c, f.matchExpected, f.then, f.otherwise))
                    } else if (f.then.isNotEmpty() || f.otherwise.isNotEmpty()) {
                        unsupported.add("fastboot commands inside a condition that is not understood")
                    }
                    if (!f.chained) break
                }
                continue
            }

            if (!low.contains("fastboot")) continue
            val cleaned = clean(line)

            val sep = SPLIT.find(cleaned)
            val left = if (sep != null) cleaned.substring(0, sep.range.first) else cleaned
            val right = if (sep != null && sep.value == "|") cleaned.substring(sep.range.last + 1) else ""

            val tokens = tokenize(left.replace(REDIRECT, ""))
            if (tokens.isEmpty()) continue
            val exe = baseName(tokens[0]).lowercase()
            if (exe == "echo" || exe == "printf") continue

            if (exe != "fastboot" && exe != "fastboot.exe" && exe != "ifastboot") {

                if (exe.contains("fastboot") || tokens.drop(1).any { baseName(it).lowercase() == "fastboot" }) {
                    unsupported.add(line)
                }
                continue
            }

            var slotAll = false
            val args = ArrayList<String>()
            var bad = false
            for (t in tokens.drop(1)) {
                when {
                    HOST_ARG.matches(t) -> {}
                    t == "--slot=all" -> slotAll = true
                    t.startsWith("-") && args.isEmpty() -> bad = true
                    t.startsWith("--") -> bad = true
                    else -> args.add(t)
                }
            }

            var grepPattern: String? = null
            if (right.isNotBlank()) {
                val rt = tokenize(right)
                if (rt.isNotEmpty() && baseName(rt[0]) == "grep") {
                    grepPattern = rt.drop(1).firstOrNull { !it.startsWith("-") }
                }
            }

            if (args.isNotEmpty() && args[0] == "getvar") {
                pending = null
                if (args.size == 2 && grepPattern != null) {
                    try {
                        pending = Cond.Getvar(args[1], Regex(grepPattern))
                    } catch (_: Exception) {

                    }
                }
                continue
            }
            if (args.size >= 2 && args[0] == "oem" && grepPattern != null) {

                pending = null
                try {
                    pending = Cond.CommandOutput(args.joinToString(" "), Regex(grepPattern))
                } catch (_: Exception) {
                    unsupported.add(line)
                }
                continue
            }

            pending = null
            if (bad || args.isEmpty()) {
                unsupported.add(line)
                continue
            }

            val o = out()
            when (args[0]) {
                "flash" ->
                    if (args.size == 3) o.add(Step.Flash(args[1], baseName(args[2]), slotAll)) else unsupported.add(line)
                "erase" ->
                    if (args.size == 2) o.add(Step.Erase(args[1])) else unsupported.add(line)
                "set_active" ->
                    if (args.size == 2) o.add(Step.SetActive(args[1])) else unsupported.add(line)
                "reboot" ->
                    o.add(Step.Reboot(args.getOrNull(1)))
                "oem", "flashing" ->
                    o.add(Step.Raw(args.joinToString(" ")))
                "delete-logical-partition" ->
                    if (args.size == 2) o.add(Step.Raw("delete-logical-partition:${args[1]}")) else unsupported.add(line)
                "create-logical-partition", "resize-logical-partition" ->
                    if (args.size == 3) o.add(Step.Raw("${args[0]}:${args[1]}:${args[2]}")) else unsupported.add(line)
                "snapshot-update" ->
                    if (args.size == 2) o.add(Step.Raw("snapshot-update:${args[1]}")) else unsupported.add(line)
                "continue" ->
                    o.add(Step.Raw("continue"))
                else -> unsupported.add(line)
            }
        }

        if (stack.isNotEmpty()) unsupported.add("the script has an unclosed if block")

        val lower = text.lowercase()
        return ParsedScript(
            steps = root,
            antiVersion = anti,
            hasAntiCheck = anti != null || lower.contains("anti_version.txt"),
            hasSecurityPatchCheck = lower.contains("platform_security_patch.txt"),
            unsupported = unsupported,
        )
    }
}
