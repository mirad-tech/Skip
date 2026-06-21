package com.example.skip.util

internal object SimpleJson {
    fun parseObject(text: String, maxNestingDepth: Int = Int.MAX_VALUE): SimpleJsonObject {
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        val parser = Parser(text, maxNestingDepth)
        val value = parser.parse()
        return value as? SimpleJsonObject ?: error("JSON root must be an object")
    }

    private class Parser(
        private val text: String,
        private val maxNestingDepth: Int
    ) {
        private var index = 0
        private var nestingDepth = 0

        fun parse(): SimpleJsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) error("Unexpected trailing content at $index")
            return value
        }

        private fun parseValue(): SimpleJsonValue {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObjectValue()
                '[' -> parseArrayValue()
                '"' -> SimpleJsonString(parseString())
                't' -> {
                    expectLiteral("true")
                    SimpleJsonBoolean(true)
                }
                'f' -> {
                    expectLiteral("false")
                    SimpleJsonBoolean(false)
                }
                'n' -> {
                    expectLiteral("null")
                    SimpleJsonNull
                }
                else -> parseNumber()
            }
        }

        private fun parseObjectValue(): SimpleJsonObject {
            enterContainer()
            try {
                return parseObjectValueWithinDepth()
            } finally {
                nestingDepth--
            }
        }

        private fun parseObjectValueWithinDepth(): SimpleJsonObject {
            expect('{')
            val values = linkedMapOf<String, SimpleJsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return SimpleJsonObject(values)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return SimpleJsonObject(values)
                    }
                    else -> error("Expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArrayValue(): SimpleJsonArray {
            enterContainer()
            try {
                return parseArrayValueWithinDepth()
            } finally {
                nestingDepth--
            }
        }

        private fun parseArrayValueWithinDepth(): SimpleJsonArray {
            expect('[')
            val values = mutableListOf<SimpleJsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return SimpleJsonArray(values)
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return SimpleJsonArray(values)
                    }
                    else -> error("Expected ',' or ']' at $index")
                }
            }
        }

        private fun enterContainer() {
            if (nestingDepth >= maxNestingDepth) error("JSON 嵌套过深")
            nestingDepth++
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(parseEscape())
                    else -> builder.append(char)
                }
            }
            error("Unterminated string")
        }

        private fun parseEscape(): Char {
            if (index >= text.length) error("Unterminated escape")
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = text.substring(index, (index + 4).coerceAtMost(text.length))
                    if (hex.length != 4) error("Invalid unicode escape")
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> error("Invalid escape: $char")
            }
        }

        private fun parseNumber(): SimpleJsonNumber {
            val start = index
            if (peek() == '-') index++
            while (peekOrNull()?.isDigit() == true) index++
            if (peekOrNull() == '.') {
                index++
                while (peekOrNull()?.isDigit() == true) index++
            }
            val exponent = peekOrNull()
            if (exponent == 'e' || exponent == 'E') {
                index++
                val sign = peekOrNull()
                if (sign == '+' || sign == '-') index++
                while (peekOrNull()?.isDigit() == true) index++
            }
            if (start == index) error("Expected value at $index")
            return SimpleJsonNumber(text.substring(start, index).toDouble())
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) error("Expected $literal at $index")
            index += literal.length
        }

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) error("Expected '$char' at $index")
            index++
        }

        private fun peek(): Char {
            return peekOrNull() ?: error("Unexpected end of JSON")
        }

        private fun peekOrNull(): Char? {
            return text.getOrNull(index)
        }

        private fun skipWhitespace() {
            while (peekOrNull()?.isWhitespace() == true) index++
        }
    }
}

internal sealed interface SimpleJsonValue
internal data class SimpleJsonObject(val values: Map<String, SimpleJsonValue>) : SimpleJsonValue {
    fun has(key: String): Boolean = values.containsKey(key)
    fun optString(key: String, default: String = ""): String {
        return when (val value = values[key]) {
            is SimpleJsonString -> value.value
            is SimpleJsonNumber -> value.value.toJsonString()
            is SimpleJsonBoolean -> value.value.toString()
            else -> default
        }
    }

    fun optInt(key: String, default: Int = 0): Int {
        return when (val value = values[key]) {
            is SimpleJsonNumber -> value.value.toInt()
            is SimpleJsonString -> value.value.toIntOrNull() ?: default
            else -> default
        }
    }

    fun optLong(key: String, default: Long = 0L): Long {
        return when (val value = values[key]) {
            is SimpleJsonNumber -> value.value.toLong()
            is SimpleJsonString -> value.value.toLongOrNull() ?: default
            else -> default
        }
    }

    fun optDouble(key: String, default: Double = 0.0): Double {
        return when (val value = values[key]) {
            is SimpleJsonNumber -> value.value
            is SimpleJsonString -> value.value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    fun optBoolean(key: String, default: Boolean = false): Boolean {
        return when (val value = values[key]) {
            is SimpleJsonBoolean -> value.value
            is SimpleJsonString -> value.value.toBooleanStrictOrNull() ?: default
            else -> default
        }
    }

    fun optJSONObject(key: String): SimpleJsonObject? = values[key] as? SimpleJsonObject
    fun optJSONArray(key: String): SimpleJsonArray? = values[key] as? SimpleJsonArray
}

internal data class SimpleJsonArray(val values: List<SimpleJsonValue>) : SimpleJsonValue {
    fun length(): Int = values.size
    fun optJSONObject(index: Int): SimpleJsonObject? = values.getOrNull(index) as? SimpleJsonObject
    fun optString(index: Int): String {
        return when (val value = values.getOrNull(index)) {
            is SimpleJsonString -> value.value
            is SimpleJsonNumber -> value.value.toJsonString()
            is SimpleJsonBoolean -> value.value.toString()
            else -> ""
        }
    }
}

internal data class SimpleJsonString(val value: String) : SimpleJsonValue
internal data class SimpleJsonNumber(val value: Double) : SimpleJsonValue
internal data class SimpleJsonBoolean(val value: Boolean) : SimpleJsonValue
internal data object SimpleJsonNull : SimpleJsonValue

private fun Double.toJsonString(): String {
    val longValue = toLong()
    return if (this == longValue.toDouble()) longValue.toString() else toString()
}
