package com.calculator.scientific

import java.text.DecimalFormat
import kotlin.math.*

enum class AngleUnit {
    DEG, RAD, GRAD
}

class CalculatorEngine {

    var angleUnit: AngleUnit = AngleUnit.DEG
    var lastAnswer: Double = 0.0
    var memoryValue: Double = 0.0
    private var engExponentOffset: Int = 0

    fun evaluate(rawExpression: String): Double {
        engExponentOffset = 0
        val expr = preprocess(rawExpression)
        if (expr.isBlank()) return 0.0
        val parser = ExpressionParser(expr)
        val result = parser.parse()
        if (!result.isNaN() && !result.isInfinite()) {
            lastAnswer = result
        }
        return result
    }

    fun formatResult(value: Double, useEngNotation: Boolean = false): String {
        if (value.isNaN() || value.isInfinite()) return "Math ERROR"
        if (value == 0.0) return "0."

        if (useEngNotation) {
            val exp3 = floor(log10(abs(value)) / 3.0).toInt() * 3 + engExponentOffset
            val mantissa = value / 10.0.pow(exp3.toDouble())
            val df = DecimalFormat("#.########")
            return "${df.format(mantissa)}×10^$exp3"
        }

        val absVal = abs(value)
        return if (absVal >= 1e10 || (absVal > 0 && absVal < 1e-9)) {
            val df = DecimalFormat("0.########E0")
            df.format(value).replace("E", "×10^")
        } else {
            val df = DecimalFormat("#.##########")
            val formatted = df.format(value)
            if (formatted.contains(".")) formatted else "$formatted."
        }
    }

    fun shiftEngNotation(value: Double): String {
        engExponentOffset += 3
        return formatResult(value, useEngNotation = true)
    }

    fun formatAsDms(decimalDegrees: Double): String {
        if (decimalDegrees.isNaN() || decimalDegrees.isInfinite()) return "Math ERROR"
        val sign = if (decimalDegrees < 0) "-" else ""
        val absDeg = abs(decimalDegrees)
        val deg = floor(absDeg).toInt()
        val minFull = (absDeg - deg) * 60.0
        val min = floor(minFull).toInt()
        val sec = ((minFull - min) * 60.0 * 100.0).roundToInt() / 100.0
        return "$sign${deg}°${min}’${sec}”"
    }

    fun formatAsFraction(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Math ERROR"
        val tolerance = 1.0E-7
        val sign = if (value < 0) "-" else ""
        val x = abs(value)
        var h1 = 1L
        var h2 = 0L
        var k1 = 0L
        var k2 = 1L
        var b = x
        var iterations = 0
        do {
            val a = floor(b).toLong()
            val auxH = h1
            h1 = a * h1 + h2
            h2 = auxH
            val auxK = k1
            k1 = a * k1 + k2
            k2 = auxK
            val diff = b - a
            if (diff < tolerance || iterations++ > 25) break
            b = 1.0 / diff
        } while (abs(x - h1.toDouble() / k1.toDouble()) > x * tolerance)

        if (k1 <= 1L || k1 > 9999L) return formatResult(value)
        val whole = h1 / k1
        val rem = h1 % k1
        return if (whole > 0 && rem > 0) {
            "$sign${whole}⌟${rem}⌟${k1}"
        } else {
            "$sign${h1}⌟${k1}"
        }
    }

    private fun preprocess(input: String): String {
        var s = input.trim()
        s = s.replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace("(−)", "-")
            .replace("π", Math.PI.toString())
            .replace("Ans", "(${lastAnswer})")
            .replace("M", "(${memoryValue})")
            .replace("³√(", "cbrt(")
            .replace("√(", "sqrt(")
            .replace("√", "sqrt")
            .replace("sin⁻¹(", "asin(")
            .replace("cos⁻¹(", "acos(")
            .replace("tan⁻¹(", "atan(")
            .replace("10^(", "tenpow(")
            .replace("e^(", "exp(")
            .replace("⌟", "/")

        // Auto-close unmatched parentheses
        val openCount = s.count { it == '(' }
        val closeCount = s.count { it == ')' }
        if (openCount > closeCount) {
            s += ")".repeat(openCount - closeCount)
        }
        return s
    }

    private fun toRadiansAngle(v: Double): Double = when (angleUnit) {
        AngleUnit.DEG -> Math.toRadians(v)
        AngleUnit.RAD -> v
        AngleUnit.GRAD -> v * Math.PI / 200.0
    }

    private fun fromRadiansAngle(rad: Double): Double = when (angleUnit) {
        AngleUnit.DEG -> Math.toDegrees(rad)
        AngleUnit.RAD -> rad
        AngleUnit.GRAD -> rad * 200.0 / Math.PI
    }

    private fun factorial(n: Double): Double {
        if (n < 0 || n != floor(n) || n > 170) return Double.NaN
        var res = 1.0
        for (i in 2..n.toInt()) res *= i.toDouble()
        return res
    }

    private fun nCr(n: Double, r: Double): Double {
        if (n < 0 || r < 0 || r > n || n != floor(n) || r != floor(r)) return Double.NaN
        return factorial(n) / (factorial(r) * factorial(n - r))
    }

    private fun nPr(n: Double, r: Double): Double {
        if (n < 0 || r < 0 || r > n || n != floor(n) || r != floor(r)) return Double.NaN
        return factorial(n) / factorial(n - r)
    }

    private inner class ExpressionParser(private val str: String) {
        private var pos = -1
        private var ch = 0

        private fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        private fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < str.length) return Double.NaN
            return x
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseInfixCombinatorics()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseInfixCombinatorics()
                    eat('/'.code) -> {
                        val denom = parseInfixCombinatorics()
                        if (denom == 0.0) return Double.NaN
                        x /= denom
                    }
                    else -> return x
                }
            }
        }

        private fun parseInfixCombinatorics(): Double {
            var x = parseFactor()
            while (true) {
                while (ch == ' '.code) nextChar()
                if (pos < str.length && (str.startsWith("C", pos) || str.startsWith("P", pos))) {
                    val isComb = str[pos] == 'C'
                    nextChar()
                    val r = parseFactor()
                    x = if (isComb) nCr(x, r) else nPr(x, r)
                } else {
                    return x
                }
            }
        }

        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                if (eat(','.code)) {
                    // Tuple support for Pol(x,y) or Rec(r,theta)
                    val y = parseExpression()
                    eat(')'.code)
                    return hypot(x, y)
                }
                eat(')'.code)
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDoubleOrNull() ?: return Double.NaN
                // Support DMS notation e.g. 12°30°15°
                if (eat('°'.code)) {
                    var deg = x
                    if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                        val mStart = pos
                        while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                        val min = str.substring(mStart, pos).toDoubleOrNull() ?: 0.0
                        deg += min / 60.0
                        if (eat('°'.code) && ((ch in '0'.code..'9'.code) || ch == '.'.code)) {
                            val sStart = pos
                            while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                            val sec = str.substring(sStart, pos).toDoubleOrNull() ?: 0.0
                            deg += sec / 3600.0
                            eat('°'.code)
                        }
                    }
                    x = deg
                }
            } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
                while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) nextChar()
                val func = str.substring(startPos, pos)
                if (eat('('.code)) {
                    val arg1 = parseExpression()
                    var arg2: Double? = null
                    if (eat(','.code)) {
                        arg2 = parseExpression()
                    }
                    eat(')'.code)
                    x = applyFunction(func, arg1, arg2)
                } else {
                    val arg1 = parseFactor()
                    x = applyFunction(func, arg1, null)
                }
            } else {
                return Double.NaN
            }

            // Postfix operators: ^, ², ³, ⁻¹, !, E
            while (true) {
                when {
                    eat('^'.code) -> x = x.pow(parseFactor())
                    eat('²'.code) -> x = x * x
                    eat('³'.code) -> x = x * x * x
                    eat('!'.code) -> x = factorial(x)
                    eat('E'.code) -> {
                        val exp = parseFactor()
                        x *= 10.0.pow(exp)
                    }
                    pos + 1 < str.length && str.substring(pos, pos + 2) == "⁻¹" -> {
                        nextChar()
                        nextChar()
                        if (x == 0.0) return Double.NaN
                        x = 1.0 / x
                    }
                    else -> break
                }
            }

            return x
        }

        private fun applyFunction(func: String, a: Double, b: Double?): Double {
            return when (func.lowercase()) {
                "sqrt" -> if (a < 0) Double.NaN else sqrt(a)
                "cbrt" -> cbrt(a)
                "sin" -> sin(toRadiansAngle(a))
                "cos" -> cos(toRadiansAngle(a))
                "tan" -> tan(toRadiansAngle(a))
                "asin" -> fromRadiansAngle(asin(a))
                "acos" -> fromRadiansAngle(acos(a))
                "atan" -> fromRadiansAngle(atan(a))
                "sinh" -> sinh(a)
                "cosh" -> cosh(a)
                "tanh" -> tanh(a)
                "log" -> if (a <= 0) Double.NaN else log10(a)
                "ln" -> if (a <= 0) Double.NaN else ln(a)
                "tenpow" -> 10.0.pow(a)
                "exp" -> exp(a)
                "pol" -> if (b != null) hypot(a, b) else abs(a)
                "rec" -> if (b != null) a * cos(toRadiansAngle(b)) else a
                else -> Double.NaN
            }
        }
    }
}
