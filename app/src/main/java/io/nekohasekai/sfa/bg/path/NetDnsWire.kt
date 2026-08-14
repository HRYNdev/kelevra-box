package io.nekohasekai.sfa.bg.path

/**
 * Сборка вопроса и разбор ответа DNS. Без Android внутри — чтобы поведение проверялось
 * тестом, а не пересказом.
 *
 * Зачем свой разбор, когда в системе есть резолвер. Опознание дома держится на одном
 * вопросе: «кто нам сейчас отвечает про имена — домашний роутер или кто-то другой».
 * Системный резолвер на этот вопрос отвечать не обязан и не отвечает:
 *
 *  - при поднятом туннеле правило per-uid уводит наши запросы в собственный tun, и про
 *    обстановку вокруг они больше не говорят ничего (та же болезнь, от которой написан
 *    [ProbeSocket] для сокетов проб);
 *  - на сорвавшемся запросе он молча отдаёт кеш, а в кеше лежат адреса прошлой сети;
 *  - «не ответил» и «ответил, что адрес настоящий» приходят одинаковым пустым списком.
 *
 * Поэтому спрашиваем сами: обычный UDP-пакет на 53-й порт резолверов той сети, про
 * которую спрашиваем, сокет защищён от своего tun. Тогда ответ либо есть и он от
 * роутера, либо его нет — и это разные вещи, а не одна.
 *
 * Формат: RFC 1035. Нам нужен минимум — вопрос об A-записи и адреса из ответа, поэтому
 * разбор намеренно узкий: он умеет пропускать то, чего не понимает, и обязан не падать
 * на мусоре (в UDP на 53-й порт прилетает что угодно, включая чужие ответы и обрезки).
 */
internal object NetDnsWire {

    /** Заголовок: id, флаги, четыре счётчика по два байта. */
    private const val HEADER_BYTES = 12

    /** Метка длиннее 63 байт невозможна: старшие два бита заняты признаком сжатия. */
    private const val LABEL_MAX = 63

    /** Два старших бита в 11 — указатель на другое место пакета (сжатие имён). */
    private const val POINTER_MASK = 0xC0

    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /** Длина A-записи: адрес IPv4. */
    private const val IPV4_BYTES = 4

    /**
     * Потолок прыжков по указателям сжатия. Пакет может ссылаться сам на себя, и без
     * потолка разбор зациклится на первом же злом (или битом) ответе.
     */
    private const val POINTER_HOPS_MAX = 16

    /** Что вернулось на наш вопрос. */
    sealed interface Reply {

        /**
         * Резолвер ответил. Список может быть пустым: это законный ответ «такого имени
         * нет» или «есть, но не A-записью», и он тоже говорит нам, что резолвер живой.
         */
        data class Answered(val addresses: List<ByteArray>) : Reply {
            // ByteArray в data class ломает equals/hashCode; сравнивать эти ответы негде,
            // а прятать грабли за автогенерацией не стоит.
            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)

            override fun toString(): String =
                "ответ, адресов ${addresses.size}: " + addresses.joinToString { text(it) }
        }

        /** Пакет пришёл, но это не ответ на наш вопрос (чужой id, не тот вид пакета). */
        data class NotOurs(val reason: String) : Reply

        /** Пакет разобрать не вышло: обрезан, зациклен, врёт про свои длины. */
        data class Broken(val reason: String) : Reply
    }

    /**
     * Вопрос об A-записи.
     *
     * Рекурсию просим (`recursion desired`): домашний роутер сам никаких зон не держит,
     * он именно рекурсивный резолвер, и без этого бита ответил бы отказом.
     */
    fun query(id: Int, host: String): ByteArray {
        val labels = host.trim('.').split('.').filter { it.isNotEmpty() }
        require(labels.isNotEmpty()) { "пустое имя" }
        require(labels.all { it.length <= LABEL_MAX }) { "метка длиннее $LABEL_MAX байт" }
        val size = HEADER_BYTES + labels.sumOf { it.length + 1 } + 1 + 4
        val packet = ByteArray(size)
        packet[0] = (id shr 8).toByte()
        packet[1] = id.toByte()
        packet[2] = 0x01 // recursion desired
        packet[5] = 0x01 // вопросов: 1
        var at = HEADER_BYTES
        for (label in labels) {
            packet[at++] = label.length.toByte()
            for (char in label) {
                // Имена берём только свои, из кода, поэтому проверять на не-ASCII негде:
                // сюда попадают gosuslugi.ru и подобные.
                packet[at++] = char.code.toByte()
            }
        }
        packet[at++] = 0 // конец имени
        packet[at++] = 0
        packet[at++] = TYPE_A.toByte()
        packet[at++] = 0
        packet[at] = CLASS_IN.toByte()
        return packet
    }

    /**
     * Разбирает ответ и достаёт адреса IPv4.
     *
     * @param size сколько байт реально пришло; массив может быть длиннее.
     * @param expectedId id нашего вопроса. Чужой id — не наш ответ: на 53-й порт
     *   прилетает и то, чего мы не спрашивали.
     */
    fun parse(packet: ByteArray, size: Int, expectedId: Int): Reply {
        if (size < HEADER_BYTES) return Reply.Broken("пакет короче заголовка ($size байт)")
        val id = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        if (id != expectedId) return Reply.NotOurs("id ответа $id, спрашивали $expectedId")
        val flags = packet[2].toInt() and 0xFF
        if (flags and 0x80 == 0) return Reply.NotOurs("это не ответ, а вопрос")
        val questions = readShort(packet, 4)
        val answers = readShort(packet, 6)

        var at = HEADER_BYTES
        repeat(questions) {
            at = skipName(packet, size, at) ?: return Reply.Broken("вопрос обрывается")
            at += 4 // тип и класс
            if (at > size) return Reply.Broken("вопрос обрывается на типе")
        }

        val found = ArrayList<ByteArray>(answers)
        repeat(answers) {
            at = skipName(packet, size, at) ?: return Reply.Broken("имя записи обрывается")
            if (at + 10 > size) return Reply.Broken("запись обрывается на заголовке")
            val type = readShort(packet, at)
            val klass = readShort(packet, at + 2)
            val length = readShort(packet, at + 8)
            at += 10
            if (at + length > size) return Reply.Broken("запись обещает $length байт, а их нет")
            // CNAME и всё остальное пропускаем молча: нам нужен адрес, а не цепочка имён.
            if (type == TYPE_A && klass == CLASS_IN && length == IPV4_BYTES) {
                found.add(packet.copyOfRange(at, at + IPV4_BYTES))
            }
            at += length
        }
        return Reply.Answered(found)
    }

    /** Адрес в привычном виде — для логов и тестов. */
    fun text(address: ByteArray): String = address.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun readShort(packet: ByteArray, at: Int): Int =
        ((packet[at].toInt() and 0xFF) shl 8) or (packet[at + 1].toInt() and 0xFF)

    /**
     * Пропускает имя и возвращает позицию сразу за ним, либо `null`, если имя не
     * помещается в пакет.
     *
     * Указатель сжатия заканчивает имя: дальше по нему прыгать незачем, само имя нам не
     * нужно — нужна только позиция следующего поля.
     */
    private fun skipName(packet: ByteArray, size: Int, from: Int): Int? {
        var at = from
        var hops = 0
        while (true) {
            if (at >= size) return null
            val length = packet[at].toInt() and 0xFF
            when {
                length == 0 -> return at + 1
                length and POINTER_MASK == POINTER_MASK -> {
                    if (at + 2 > size) return null
                    if (++hops > POINTER_HOPS_MAX) return null
                    return at + 2
                }
                length > LABEL_MAX -> return null
                else -> at += length + 1
            }
        }
    }
}
