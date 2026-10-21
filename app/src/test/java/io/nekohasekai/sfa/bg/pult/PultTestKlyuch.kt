package io.nekohasekai.sfa.bg.pult

import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Пробный ключ ed25519 для тестов — свой, из JDK. Боевые закрытые ключи пульта живут
 * только в мозге и сюда не попадают (PULT.md, «Подпись»).
 */
internal class PultTestKlyuch {
    private val para = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Сырые 32 байта открытого ключа: хвост X.509-обёртки. */
    val otkrytyy: ByteArray = para.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    fun podpisat(bytes: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(para.private)
        update(bytes)
        sign()
    }

    /** Блок `pult` из /info: base64 данных и подпись строки `kelevra-pult-v1\n` + данные. */
    fun blok(dannye: JSONObject): JSONObject = blok(dannye.toString().toByteArray(Charsets.UTF_8))

    fun blok(bytes: ByteArray): JSONObject {
        val podpis = podpisat(PultYadro.PREFIKS_PODPISI.toByteArray(Charsets.UTF_8) + bytes)
        return JSONObject().put("dannye", PultBase64.zakodirovat(bytes)).put("podpis", PultBase64.zakodirovat(podpis))
    }
}

internal const val USTROYSTVO = "kelevra-test-ustroystvo"

internal fun dannye(
    versiya: Long,
    nastroyki: JSONObject = JSONObject(),
    komandy: org.json.JSONArray = org.json.JSONArray(),
    komu: String = USTROYSTVO,
    v: Int = 1,
    obnovlenie: JSONObject? = null,
): JSONObject = JSONObject()
    .put("v", v)
    .put("versiya", versiya)
    .put("vydano", "2026-10-21T12:00:00Z")
    .put("komu", komu)
    .put("nastroyki", nastroyki)
    .put("komandy", komandy)
    .apply { if (obnovlenie != null) put("obnovlenie", obnovlenie) }

internal fun komanda(id: String, chto: String, srok: String? = "2099-01-01T00:00:00Z", param: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("id", id).put("chto", chto).put("param", param).apply { if (srok != null) put("do", srok) }
