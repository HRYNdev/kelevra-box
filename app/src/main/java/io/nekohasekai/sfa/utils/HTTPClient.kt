package io.nekohasekai.sfa.utils

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.Kelevra
import io.nekohasekai.sfa.ktx.unwrap
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        val userAgent by lazy {
            var userAgent = "Kelevra/"
            userAgent += BuildConfig.VERSION_NAME
            userAgent += " ("
            userAgent += BuildConfig.VERSION_CODE
            userAgent += "; sing-box-xhttp "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun getString(url: String): String {
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        // Панель ведёт учёт устройств по этим заголовкам. Без них наш клиент для неё
        // безымянный, и ограничение по числу устройств не работает в принципе.
        if (url.contains(Kelevra.SUBSCRIPTION_HOST)) {
            request.setHeader("x-hwid", Kelevra.deviceId)
            request.setHeader("x-device-os", "Android")
            request.setHeader("x-ver-os", Build.VERSION.RELEASE ?: "")
            request.setHeader("x-device-model", Build.MODEL ?: "")
            // Своё имя устройства. По этим четырём заголовкам сервер сам заводит
            // устройство при первом же запросе конфига и потом возвращает в /info
            // человеческие имена — чьё это устройство и как оно называется.
            Kelevra.deviceHeaders().forEach { (name, value) -> request.setHeader(name, value) }
        }
        val response = request.execute()
        return response.content.unwrap
    }

    override fun close() {
        client.close()
    }
}
