package io.nekohasekai.sfa

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Поле кода: в состоянии лежит сырое значение, группы по 4 через дефис только рисуются.
 * Так порядок символов не портится при вводе и курсор не прыгает.
 */
object KodPole {
    private const val DLINA = 12

    private fun ascii(c: Char) = c.isLetterOrDigit() && c.code < 128

    /** Сырое значение: без пробелов и мусора; ссылка не трогается. Регистр сохраняется (старые коды его различают). */
    fun syroe(vvod: String): String {
        if (Priglashenie.etoSsylka(vvod)) return vvod.trim()
        return vvod.filter { ascii(it) || it == '-' || it == '_' }
    }

    /** Вставка из буфера: как syroe, а у нового кода (до 12 знаков, без _) дефисы убираются совсем. */
    fun vstavka(vvod: String): String {
        val s = syroe(vvod)
        return if (Priglashenie.etoSsylka(s) || !novyj(s)) s else s.filter { it != '-' }
    }

    /** Новый код: без _ и не больше 12 букв и цифр; только такой показывается группами. */
    fun novyj(syroe: String): Boolean =
        syroe.none { it == '_' } && syroe.count { it != '-' } <= DLINA

    class Pokaz(val text: String, val syroeVPokaz: IntArray, val pokazVSyroe: IntArray)

    fun pokaz(syroe: String): Pokaz {
        val n = syroe.length
        if (Priglashenie.etoSsylka(syroe) || !novyj(syroe)) {
            return Pokaz(syroe, IntArray(n + 1) { it }, IntArray(n + 1) { it })
        }
        val sb = StringBuilder()
        val kept = ArrayList<Int>() // индекс в сыром для каждого видимого знака (не дефис)
        val pozicii = ArrayList<Int>() // позиция этого знака в показе
        var k = 0
        for (i in 0 until n) {
            val c = syroe[i]
            if (c == '-') continue
            if (k > 0 && k % 4 == 0) sb.append('-')
            pozicii.add(sb.length)
            kept.add(i)
            sb.append(c.uppercaseChar())
            k++
        }
        val len = sb.length
        // сырой -> показ: позиция ближайшего видимого знака справа; в конце — длина показа
        val vPokaz = IntArray(n + 1)
        var next = 0
        for (i in 0 until n) {
            while (next < kept.size && kept[next] < i) next++
            vPokaz[i] = if (next < pozicii.size) pozicii[next] else len
        }
        vPokaz[n] = len
        // показ -> сырой: первый видимый знак, чья позиция не меньше p
        val vSyroe = IntArray(len + 1)
        var j = 0
        for (p in 0..len) {
            while (j < pozicii.size && pozicii[j] < p) j++
            vSyroe[p] = if (j < kept.size) kept[j] else n
        }
        return Pokaz(sb.toString(), vPokaz, vSyroe)
    }

    val transformation = VisualTransformation { text ->
        val p = pokaz(text.text)
        TransformedText(
            AnnotatedString(p.text),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) =
                    p.syroeVPokaz[offset.coerceIn(0, p.syroeVPokaz.size - 1)]

                override fun transformedToOriginal(offset: Int) =
                    p.pokazVSyroe[offset.coerceIn(0, p.pokazVSyroe.size - 1)]
            },
        )
    }
}
