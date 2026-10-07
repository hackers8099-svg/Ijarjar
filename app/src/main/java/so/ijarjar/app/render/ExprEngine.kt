package so.ijarjar.app.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small After Effects style expression language (JavaScript-like):
 *   wiggle(2, 30)            loopOut("cycle")           value + [0, Math.sin(time*3)*40]
 *   bounce(0.05, 3, 6)       linear(time, 0, 1, 0, 100) time % 1 < 0.5 ? 100 : 0
 *   freq = 2; amp = 25; wiggle(freq, amp)
 * Values are numbers or arrays ([x, y]); maths works element by element like in AE.
 */
object ExprEngine {

    /** Composition size in pixels (positions in expressions are in pixels, like AE). */
    @Volatile var compW = 1080.0
    @Volatile var compH = 1920.0

    // ------------------------------------------------------------------ context

    class Ctx(
        var time: Double,
        val value: DoubleArray,
        val valueAt: (Double) -> DoubleArray,
        val keyTimes: List<Double>,
        val duration: Double,
        val compW: Double,
        val compH: Double
    ) {
        val vars = HashMap<String, DoubleArray>()
    }

    class ExprError(msg: String) : Exception(msg)

    // ------------------------------------------------------------------ tokens

    private enum class T { NUM, ID, STR, OP, END }
    private class Tok(val t: T, val s: String, val n: Double = 0.0)

    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val ops3 = listOf("===", "!==")
        val ops2 = listOf("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=")
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> { i += 2; while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++; i += 2 }
                c == '\n' -> { out.add(Tok(T.OP, ";")); i++ }
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val s = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) { i++; if (i < src.length && (src[i] == '-' || src[i] == '+')) i++; while (i < src.length && src[i].isDigit()) i++ }
                    out.add(Tok(T.NUM, src.substring(s, i), src.substring(s, i).toDoubleOrNull() ?: throw ExprError("Bad number")))
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val s = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$' || src[i] == '.')) i++
                    var id = src.substring(s, i).trimEnd('.')
                    i = s + id.length
                    for (p in listOf("Math.", "thisProperty.", "thisLayer.", "transform.", "thisComp.")) if (id.startsWith(p)) id = id.removePrefix(p)
                    out.add(Tok(T.ID, id))
                }
                c == '"' || c == '\'' -> {
                    val q = c; i++
                    val s = i
                    while (i < src.length && src[i] != q) i++
                    out.add(Tok(T.STR, src.substring(s, minOf(i, src.length)))); i++
                }
                else -> {
                    val three = if (i + 3 <= src.length) src.substring(i, i + 3) else ""
                    val two = if (i + 2 <= src.length) src.substring(i, i + 2) else ""
                    when {
                        three in ops3 -> { out.add(Tok(T.OP, three.dropLast(1))); i += 3 }
                        two in ops2 -> { out.add(Tok(T.OP, two)); i += 2 }
                        c in "+-*/%()[],;?:<>!=" -> { out.add(Tok(T.OP, c.toString())); i++ }
                        else -> throw ExprError("Unexpected '$c'")
                    }
                }
            }
        }
        out.add(Tok(T.END, ""))
        return out
    }

    // ------------------------------------------------------------------ syntax tree

    private sealed class N {
        class Num(val v: Double) : N()
        class Str(val s: String) : N()
        class Var(val name: String) : N()
        class Arr(val items: List<N>) : N()
        class Un(val op: String, val a: N) : N()
        class Bin(val op: String, val a: N, val b: N) : N()
        class Call(val name: String, val args: List<N>) : N()
        class Idx(val a: N, val i: N) : N()
        class Tern(val c: N, val a: N, val b: N) : N()
        class Assign(val name: String, val op: String, val v: N) : N()
        class Seq(val list: List<N>) : N()
    }

    private class Parser(val t: List<Tok>) {
        var p = 0
        fun peek() = t[p]
        fun isOp(s: String) = t[p].t == T.OP && t[p].s == s
        fun eat(s: String) { if (!isOp(s)) throw ExprError("Expected '$s'"); p++ }

        fun program(): N {
            val list = ArrayList<N>()
            while (peek().t != T.END) {
                if (isOp(";")) { p++; continue }
                list.add(statement())
            }
            if (list.isEmpty()) throw ExprError("Empty")
            return if (list.size == 1) list[0] else N.Seq(list)
        }

        fun statement(): N {
            if (peek().t == T.ID && peek().s in setOf("var", "let", "const")) p++
            if (peek().t == T.ID && t[p + 1].t == T.OP && t[p + 1].s in setOf("=", "+=", "-=", "*=", "/=")) {
                val name = peek().s; p++
                val op = peek().s; p++
                return N.Assign(name, op, expr())
            }
            return expr()
        }

        fun expr(): N {
            val c = or()
            if (isOp("?")) { p++; val a = expr(); eat(":"); val b = expr(); return N.Tern(c, a, b) }
            return c
        }
        fun or(): N { var a = and(); while (isOp("||")) { p++; a = N.Bin("||", a, and()) }; return a }
        fun and(): N { var a = cmp(); while (isOp("&&")) { p++; a = N.Bin("&&", a, cmp()) }; return a }
        fun cmp(): N {
            var a = add()
            while (peek().t == T.OP && peek().s in setOf("==", "!=", "<", ">", "<=", ">=")) { val op = peek().s; p++; a = N.Bin(op, a, add()) }
            return a
        }
        fun add(): N {
            var a = mul()
            while (isOp("+") || isOp("-")) { val op = peek().s; p++; a = N.Bin(op, a, mul()) }
            return a
        }
        fun mul(): N {
            var a = unary()
            while (isOp("*") || isOp("/") || isOp("%")) { val op = peek().s; p++; a = N.Bin(op, a, unary()) }
            return a
        }
        fun unary(): N {
            if (isOp("-")) { p++; return N.Un("-", unary()) }
            if (isOp("+")) { p++; return unary() }
            if (isOp("!")) { p++; return N.Un("!", unary()) }
            return postfix(primary())
        }
        fun postfix(n0: N): N {
            var n = n0
            while (isOp("[")) { p++; val i = expr(); eat("]"); n = N.Idx(n, i) }
            return n
        }
        fun primary(): N {
            val k = peek()
            when (k.t) {
                T.NUM -> { p++; return N.Num(k.n) }
                T.STR -> { p++; return N.Str(k.s) }
                T.ID -> {
                    p++
                    if (isOp("(")) {
                        p++
                        val args = ArrayList<N>()
                        if (!isOp(")")) { args.add(expr()); while (isOp(",")) { p++; args.add(expr()) } }
                        eat(")")
                        return N.Call(k.s, args)
                    }
                    return N.Var(k.s)
                }
                T.OP -> {
                    if (k.s == "(") { p++; val e = expr(); eat(")"); return e }
                    if (k.s == "[") {
                        p++
                        val items = ArrayList<N>()
                        if (!isOp("]")) { items.add(expr()); while (isOp(",")) { p++; items.add(expr()) } }
                        eat("]")
                        return N.Arr(items)
                    }
                    throw ExprError("Unexpected '${k.s}'")
                }
                T.END -> throw ExprError("Unexpected end")
            }
        }
    }

    // ------------------------------------------------------------------ cache

    private val cache = HashMap<String, Any>()   // code -> N or ExprError

    /** Parses (cached). Returns an error message, or null when the code is fine. */
    fun check(code: String): String? = when (val r = parse(code)) { is ExprError -> r.message ?: "Error"; else -> null }

    private fun parse(code: String): Any = synchronized(cache) {
        cache.getOrPut(code) {
            try { Parser(lex(code)).program() } catch (e: ExprError) { e } catch (e: Exception) { ExprError(e.message ?: "Error") }
        }
    }

    /** Evaluates [code]; null if it fails (then the property keeps its keyframed value). */
    fun eval(code: String, ctx: Ctx): DoubleArray? {
        val n = parse(code) as? N ?: return null
        return try { ev(n, ctx) } catch (e: Exception) { null }
    }

    /** Like [eval] but returns the error text. */
    fun test(code: String, ctx: Ctx): String? {
        val n = parse(code)
        if (n is ExprError) return n.message
        return try { ev(n as N, ctx); null } catch (e: Exception) { e.message ?: "Error" }
    }

    // ------------------------------------------------------------------ evaluation

    private fun num(v: Double) = doubleArrayOf(v)
    private fun truthy(a: DoubleArray) = a.isNotEmpty() && a[0] != 0.0

    private inline fun zip(a: DoubleArray, b: DoubleArray, f: (Double, Double) -> Double): DoubleArray {
        if (a.size == 1) return DoubleArray(b.size) { f(a[0], b[it]) }
        if (b.size == 1) return DoubleArray(a.size) { f(a[it], b[0]) }
        val n = max(a.size, b.size)
        return DoubleArray(n) { f(a.getOrElse(it) { 0.0 }, b.getOrElse(it) { 0.0 }) }
    }

    private fun ev(n: N, c: Ctx): DoubleArray = when (n) {
        is N.Num -> num(n.v)
        is N.Str -> throw ExprError("Text isn't a value")
        is N.Var -> variable(n.name, c)
        is N.Arr -> n.items.flatMap { ev(it, c).toList() }.toDoubleArray()
        is N.Un -> { val a = ev(n.a, c); if (n.op == "-") DoubleArray(a.size) { -a[it] } else num(if (truthy(a)) 0.0 else 1.0) }
        is N.Bin -> bin(n, c)
        is N.Idx -> { val a = ev(n.a, c); val i = ev(n.i, c)[0].toInt(); num(a.getOrElse(i) { 0.0 }) }
        is N.Tern -> if (truthy(ev(n.c, c))) ev(n.a, c) else ev(n.b, c)
        is N.Assign -> {
            val v = ev(n.v, c)
            val r = when (n.op) {
                "=" -> v
                else -> { val old = variable(n.name, c); zip(old, v) { x, y -> when (n.op) { "+=" -> x + y; "-=" -> x - y; "*=" -> x * y; else -> if (y == 0.0) 0.0 else x / y } } }
            }
            c.vars[n.name] = r; r
        }
        is N.Seq -> { var r = num(0.0); for (s in n.list) r = ev(s, c); r }
        is N.Call -> call(n, c)
    }

    private fun bin(n: N.Bin, c: Ctx): DoubleArray {
        if (n.op == "&&") return if (truthy(ev(n.a, c))) num(if (truthy(ev(n.b, c))) 1.0 else 0.0) else num(0.0)
        if (n.op == "||") return if (truthy(ev(n.a, c))) num(1.0) else num(if (truthy(ev(n.b, c))) 1.0 else 0.0)
        val a = ev(n.a, c); val b = ev(n.b, c)
        return when (n.op) {
            "+" -> zip(a, b) { x, y -> x + y }
            "-" -> zip(a, b) { x, y -> x - y }
            "*" -> zip(a, b) { x, y -> x * y }
            "/" -> zip(a, b) { x, y -> if (y == 0.0) 0.0 else x / y }
            "%" -> zip(a, b) { x, y -> if (y == 0.0) 0.0 else x % y }
            "==" -> num(if (a[0] == b[0]) 1.0 else 0.0)
            "!=" -> num(if (a[0] != b[0]) 1.0 else 0.0)
            "<" -> num(if (a[0] < b[0]) 1.0 else 0.0)
            ">" -> num(if (a[0] > b[0]) 1.0 else 0.0)
            "<=" -> num(if (a[0] <= b[0]) 1.0 else 0.0)
            ">=" -> num(if (a[0] >= b[0]) 1.0 else 0.0)
            else -> throw ExprError("Unknown ${n.op}")
        }
    }

    private fun variable(name: String, c: Ctx): DoubleArray {
        c.vars[name]?.let { return it }
        return when (name) {
            "time" -> num(c.time)
            "value" -> c.value
            "PI" -> num(PI)
            "E" -> num(kotlin.math.E)
            "duration", "outPoint" -> num(c.duration)
            "inPoint" -> num(0.0)
            "width" -> num(c.compW)
            "height" -> num(c.compH)
            "index" -> num(1.0)
            "numKeys" -> num(c.keyTimes.size.toDouble())
            "frameDuration" -> num(1.0 / 30.0)
            "true" -> num(1.0)
            "false" -> num(0.0)
            else -> throw ExprError("Unknown name: $name")
        }
    }

    private fun hash(i: Long, salt: Int): Double {
        var x = (i * 374761393L + salt * 668265263L).toInt()
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535.0
    }

    /** Smooth 1D noise in -1..1. */
    private fun noise(x: Double, seed: Int): Double {
        val i = floor(x).toLong(); val f = x - i
        val a = hash(i, seed) * 2 - 1; val b = hash(i + 1, seed) * 2 - 1
        val u = f * f * (3 - 2 * f)
        return a + (b - a) * u
    }

    private fun interp(t: Double, t0: Double, t1: Double, v0: DoubleArray, v1: DoubleArray, curve: (Double) -> Double): DoubleArray {
        val f = if (t1 == t0) 1.0 else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        val k = curve(f)
        return zip(v0, v1) { a, b -> a + (b - a) * k }
    }

    private fun call(n: N.Call, c: Ctx): DoubleArray {
        val name = n.name
        fun a(i: Int) = ev(n.args[i], c)
        fun d(i: Int, def: Double) = if (i < n.args.size) ev(n.args[i], c)[0] else def
        fun str(i: Int, def: String) = (n.args.getOrNull(i) as? N.Str)?.s ?: def
        fun m1(f: (Double) -> Double): DoubleArray { val x = a(0); return DoubleArray(x.size) { f(x[it]) } }
        return when (name) {
            "sin" -> m1 { kotlin.math.sin(it) }
            "cos" -> m1 { kotlin.math.cos(it) }
            "tan" -> m1 { kotlin.math.tan(it) }
            "asin" -> m1 { kotlin.math.asin(it) }
            "acos" -> m1 { kotlin.math.acos(it) }
            "atan" -> m1 { kotlin.math.atan(it) }
            "atan2" -> num(kotlin.math.atan2(d(0, 0.0), d(1, 1.0)))
            "abs" -> m1 { abs(it) }
            "sqrt" -> m1 { sqrt(it) }
            "exp" -> m1 { exp(it) }
            "log" -> m1 { kotlin.math.ln(it) }
            "floor" -> m1 { floor(it) }
            "ceil" -> m1 { kotlin.math.ceil(it) }
            "round" -> m1 { kotlin.math.round(it) }
            "pow" -> num(Math.pow(d(0, 0.0), d(1, 1.0)))
            "min" -> num(n.args.indices.minOf { a(it)[0] })
            "max" -> num(n.args.indices.maxOf { a(it)[0] })
            "clamp" -> { val x = a(0); val lo = d(1, 0.0); val hi = d(2, 1.0); DoubleArray(x.size) { x[it].coerceIn(min(lo, hi), max(lo, hi)) } }
            "degreesToRadians" -> m1 { it * PI / 180 }
            "radiansToDegrees" -> m1 { it * 180 / PI }
            "length" -> { val x = a(0); val y = if (n.args.size > 1) a(1) else null
                val v = if (y != null) zip(x, y) { p, q -> p - q } else x; num(sqrt(v.sumOf { it * it })) }
            "seedRandom" -> { c.vars["__seed"] = num(d(0, 0.0)); num(0.0) }
            "random", "gaussRandom" -> {
                val seed = c.vars["__seed"]?.get(0)?.toLong() ?: 0L
                val frame = (c.time * 30).toLong()
                val r = hash(frame * 31 + seed * 7919, 17)
                when (n.args.size) {
                    0 -> num(r)
                    1 -> { val mx = a(0); DoubleArray(mx.size) { mx[it] * hash(frame * 31 + seed * 7919 + it, 29 + it) } }
                    else -> { val lo = a(0); val hi = a(1); zip(lo, hi) { p, q -> p + (q - p) * r } }
                }
            }
            "noise" -> num(noise(d(0, 0.0), 3))
            "wiggle" -> {
                val freq = d(0, 1.0); val amp = d(1, 10.0); val oct = d(2, 1.0).toInt().coerceIn(1, 4)
                val v = c.value
                DoubleArray(v.size) { i ->
                    var s = 0.0; var f = freq; var am = 1.0; var tot = 0.0
                    repeat(oct) { o -> s += noise(c.time * f, 11 + i * 37 + o * 101) * am; tot += am; f *= 2; am *= 0.5 }
                    v[i] + s / tot * amp
                }
            }
            "linear", "ease", "easeIn", "easeOut" -> {
                val curve: (Double) -> Double = when (name) {
                    "ease" -> { f -> f * f * (3 - 2 * f) }
                    "easeIn" -> { f -> f * f }
                    "easeOut" -> { f -> 1 - (1 - f) * (1 - f) }
                    else -> { f -> f }
                }
                if (n.args.size >= 5) interp(d(0, 0.0), d(1, 0.0), d(2, 1.0), a(3), a(4), curve)
                else interp(d(0, 0.0), 0.0, 1.0, a(1), a(2), curve)
            }
            "valueAtTime" -> c.valueAt(d(0, c.time))
            "key" -> { val i = d(0, 1.0).toInt() - 1; c.valueAt(c.keyTimes.getOrElse(i.coerceIn(0, (c.keyTimes.size - 1).coerceAtLeast(0))) { 0.0 }) }
            "posterizeTime" -> { val fps = d(0, 12.0).coerceAtLeast(0.1); c.time = floor(c.time * fps) / fps; num(fps) }
            "loopOut", "loopIn" -> loop(c, str(0, "cycle"), name == "loopIn")
            "bounce", "inertia" -> bounce(c, d(0, 0.05), d(1, 2.5), d(2, 5.0))
            "time" -> num(c.time)
            else -> throw ExprError("Unknown function: $name()")
        }
    }

    private fun loop(c: Ctx, type: String, isIn: Boolean): DoubleArray {
        val ks = c.keyTimes
        if (ks.size < 2) return c.value
        val first = ks.first(); val last = ks.last(); val span = last - first
        if (span <= 0) return c.value
        val t = c.time
        if (!isIn && t <= last) return c.value
        if (isIn && t >= first) return c.value
        val rel = if (isIn) first - t else t - last
        val k = floor(rel / span).toLong(); val r = rel % span
        return when (type.lowercase()) {
            "pingpong" -> c.valueAt(if (isIn) (if (k % 2 == 0L) first + r else last - r) else (if (k % 2 == 0L) last - r else first + r))
            "offset" -> {
                val delta = zip(c.valueAt(last), c.valueAt(first)) { p, q -> p - q }
                val base = c.valueAt(if (isIn) last - r else first + r)
                zip(base, delta) { p, q -> if (isIn) p - q * (k + 1) else p + q * (k + 1) }
            }
            "continue" -> {
                val v1 = c.valueAt(if (isIn) first else last); val v0 = c.valueAt(if (isIn) first + 0.04 else last - 0.04)
                val vel = zip(v1, v0) { p, q -> (p - q) / 0.04 }
                zip(v1, vel) { p, q -> p + q * rel }
            }
            else -> c.valueAt(if (isIn) last - r else first + r)
        }
    }

    /**
     * Inertial bounce: after each keyframe the value overshoots and settles. The speed used is the
     * average speed of the move into that keyframe, so it also works with eased keyframes.
     */
    private fun bounce(c: Ctx, amp: Double, freq: Double, decay: Double): DoubleArray {
        val ks = c.keyTimes
        val t = c.time
        if (ks.size < 2) return c.value
        val idx = ks.indexOfLast { it <= t }
        if (idx < 1) return c.value
        val kt = ks[idx]
        val dt = t - kt
        if (dt <= 0) return c.value
        if (idx + 1 < ks.size) {
            // only while holding before the next move
            val nv = c.valueAt(ks[idx + 1]); val cv = c.valueAt(kt)
            if (!nv.contentEquals(cv) && t >= ks[idx + 1]) return c.value
        }
        val prevT = ks[idx - 1]
        val v1 = c.valueAt(kt); val v0 = c.valueAt(prevT)
        val segment = (kt - prevT).coerceAtLeast(0.001)
        val vel = zip(v1, v0) { p, q -> (p - q) / segment }
        val osc = amp * sin(freq * dt * 2 * PI) / exp(decay * dt)
        return zip(c.value, vel) { p, q -> p + q * osc }
    }
}
