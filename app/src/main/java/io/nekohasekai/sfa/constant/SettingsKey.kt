package io.nekohasekai.sfa.constant

object SettingsKey {
    const val SELECTED_PROFILE = "selected_profile"
    const val SERVICE_MODE = "service_mode"
    const val CHECK_UPDATE_ENABLED = "check_update_enabled"
    const val UPDATE_CHECK_PROMPTED = "update_check_prompted"
    const val UPDATE_SOURCE = "update_source"
    const val UPDATE_TRACK = "update_track"
    const val GITHUB_TOKEN = "github_token"
    const val FDROID_MIRROR_URL = "fdroid_mirror_url"
    const val FDROID_CUSTOM_MIRRORS = "fdroid_custom_mirrors"
    const val SILENT_INSTALL_ENABLED = "silent_install_enabled"
    const val SILENT_INSTALL_METHOD = "silent_install_method"
    const val AUTO_UPDATE_ENABLED = "auto_update_enabled"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val DISABLE_DEPRECATED_WARNINGS = "disable_deprecated_warnings"

    const val AUTO_REDIRECT = "auto_redirect"
    const val PER_APP_PROXY_ENABLED = "per_app_proxy_enabled"
    const val PER_APP_PROXY_MODE = "per_app_proxy_mode"
    const val PER_APP_PROXY_LIST = "per_app_proxy_list"
    const val PER_APP_PROXY_MANAGED_MODE = "per_app_proxy_managed_mode"
    const val PER_APP_PROXY_MANAGED_LIST = "per_app_proxy_managed_list"
    const val PER_APP_PROXY_PACKAGE_QUERY_MODE = "per_app_proxy_package_query_mode"

    const val ALLOW_BYPASS = "allow_bypass"
    const val SYSTEM_PROXY_ENABLED = "system_proxy_enabled"

    const val PRIVILEGE_SETTINGS_ENABLED = "hide_settings_enabled"
    const val PRIVILEGE_SETTINGS_LIST = "hide_settings_list"
    const val PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED = "hide_settings_interface_rename_enabled"
    const val PRIVILEGE_SETTINGS_INTERFACE_PREFIX = "hide_settings_interface_prefix"

    // OOM killer
    const val OOM_KILLER_ENABLED = "oom_killer_enabled"
    const val OOM_KILLER_DISABLED = "oom_killer_disabled"
    const val OOM_MEMORY_LIMIT_MB = "oom_memory_limit_mb"

    // dashboard
    const val DASHBOARD_ITEM_ORDER = "dashboard_item_order"
    const val DASHBOARD_DISABLED_ITEMS = "dashboard_disabled_items"

    // Remote Control
    const val ACTIVE_REMOTE_SERVER_ID = "active_remote_server_id"

    // Tailscale SSH
    const val TAILSCALE_SSH_REMEMBERED_USERNAMES = "tailscale_ssh_remembered_usernames"
    const val TAILSCALE_SSH_QUICK_CONNECT_PEERS = "tailscale_ssh_quick_connect_peers"
    const val TAILSCALE_SSH_LIGHT_THEME = "tailscale_ssh_light_theme"
    const val TAILSCALE_SSH_DARK_THEME = "tailscale_ssh_dark_theme"
    const val TAILSCALE_SSH_FONT_FAMILY = "tailscale_ssh_font_family"
    const val TAILSCALE_SSH_FONT_SIZE = "tailscale_ssh_font_size"
    const val TAILSCALE_SSH_CUSTOM_FONT_PATH = "tailscale_ssh_custom_font_path"

    // olcRTC (выход через ядро olcrtc).
    // Параметры приезжают с сервера в /k/<код>/info (ключи OLCRTC_SRV_*), а эти —
    // ручное переопределение для отладки: заполнено -> берём его, пусто -> серверное.
    const val OLCRTC_ENABLED = "olcrtc_enabled"
    const val OLCRTC_CARRIER = "olcrtc_carrier"
    const val OLCRTC_ROOM_ID = "olcrtc_room_id"
    const val OLCRTC_CLIENT_ID = "olcrtc_client_id"
    const val OLCRTC_KEY_HEX = "olcrtc_key_hex"
    const val OLCRTC_TRANSPORT = "olcrtc_transport"
    const val OLCRTC_SOCKS_PORT = "olcrtc_socks_port"
    const val OLCRTC_WB_TOKEN = "olcrtc_wb_token"
    const val OLCRTC_VP8_FPS = "olcrtc_vp8_fps"
    const val OLCRTC_VP8_BATCH_SIZE = "olcrtc_vp8_batch_size"

    // Пришедшее с сервера. Токена здесь нет и не будет: он личный и на сервер не кладётся.
    const val OLCRTC_SRV_AVAILABLE = "olcrtc_srv_available"
    const val OLCRTC_SRV_CARRIER = "olcrtc_srv_carrier"
    const val OLCRTC_SRV_ROOM_ID = "olcrtc_srv_room_id"
    const val OLCRTC_SRV_CLIENT_ID = "olcrtc_srv_client_id"
    const val OLCRTC_SRV_KEY_HEX = "olcrtc_srv_key_hex"
    const val OLCRTC_SRV_TRANSPORT = "olcrtc_srv_transport"
    const val OLCRTC_SRV_WB_TOKEN = "olcrtc_srv_wb_token"
    const val OLCRTC_SRV_SOCKS_PORT = "olcrtc_srv_socks_port"
    const val OLCRTC_SRV_VP8_FPS = "olcrtc_srv_vp8_fps"
    const val OLCRTC_SRV_VP8_BATCH_SIZE = "olcrtc_srv_vp8_batch_size"

    /** Своё имя устройства в комнате: сервер его не раздаёт, а совпадать они не должны. */
    const val OLCRTC_DEVICE_ID = "olcrtc_device_id"

    /** Автомат выбирает выход сам. Выключается только выбором выхода руками. */
    const val AUTO_MODE_ENABLED = "auto_mode_enabled"

    /** Человек выбрал руками именно комнату: её ядро надо поднимать и после перезапуска. */
    const val AUTO_MODE_MANUAL_ROOM = "auto_mode_manual_room"

    /** Имя выхода, выбранного руками: выбор должен пережить выключенную сеть и перезапуск. */
    const val AUTO_MODE_MANUAL_EXIT = "auto_mode_manual_exit"

    /**
     * Селектор и тег, которые последним выставил АВТОМАТ (не человек). См. [AutoModeSticky]:
     * ядро хранит выбор в своём кэше и переживает им перезапуск наравне с ручным выбором —
     * эта пара нужна, чтобы отличить один от другого и погасить только автоматный.
     */
    const val AUTO_MODE_STICKY_GROUP = "auto_mode_sticky_group"
    const val AUTO_MODE_STICKY_TAG = "auto_mode_sticky_tag"

    /** Номер жалобы, ответ на которую человек уже прочитал: показывать его снова незачем. */
    const val COMPLAINT_REPLY_SEEN = "complaint_reply_seen"

    /** Когда логи ушли в последний раз успешно (миллисекунды). 0 — ни разу. */
    const val LOG_UPLOAD_LAST_OK = "log_upload_last_ok"

    /** Что уже отправлено: размеры, отметки времени и смещения по каждому файлу (JSON). */
    const val LOG_UPLOAD_MARKS = "log_upload_marks"

    /** Когда началась череда неудачных попыток: через сутки перестаём долбиться. */
    const val LOG_UPLOAD_RETRY_SINCE = "log_upload_retry_since"

    /** Отпечаток последнего отвергнутого хвоста логов и счётчик его подряд отказов. */
    const val LOG_UPLOAD_STUCK_SIGNATURE = "log_upload_stuck_signature"
    const val LOG_UPLOAD_STUCK_COUNT = "log_upload_stuck_count"

    /**
     * Свой расход трафика: итог за всё время (байты) и последнее показание счётчика ядра,
     * от которого считается прирост. Счётчик ядра обнуляется на каждом перезапуске,
     * а серверу нужен расход за всё время — см. [io.nekohasekai.sfa.bg.DeviceTraffic].
     */
    const val DEVICE_TRAFFIC_TOTAL = "device_traffic_total"
    const val DEVICE_TRAFFIC_SEEN = "device_traffic_seen"


    // cache
    const val STARTED_BY_USER = "started_by_user"
    const val CACHED_UPDATE_INFO = "cached_update_info"
    const val CACHED_APK_PATH = "cached_apk_path"
    const val LAST_SHOWN_UPDATE_VERSION = "last_shown_update_version"
}
