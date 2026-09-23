package io.woowtech.odoo.ui.login

/**
 * 把使用者在「伺服器網址」欄位輸入的內容整理成 `https://host[:port][/path]`。
 *
 * 輸入欄位前面已經顯示 `https://`，但使用者（包括 Google Play 審查員）常會
 * 貼上整段網址、多打一次 scheme、或帶著尾端空白。2026-09-23 的 Play 退件就是
 * `"demo222-odoo.woowtech.io "` 尾端空白讓 OkHttp 丟出 Invalid URL host。
 */
internal object ServerUrlInput {

    private const val HTTPS = "https://"
    private const val HTTP = "http://"
    private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?$")

    /** 是否明確指定了不安全的 http://（大小寫、前後空白都算）。 */
    fun isInsecure(raw: String): Boolean = raw.trim().lowercase().startsWith(HTTP)

    /**
     * 回傳正規化後的完整網址；無法組成合法主機名稱時回傳 null。
     * 呼叫端應先用 [isInsecure] 擋掉 http://。
     */
    fun normalize(raw: String): String? {
        var rest = raw.trim()
        while (rest.lowercase().startsWith(HTTPS)) {
            rest = rest.substring(HTTPS.length)
        }
        if (rest.lowercase().startsWith(HTTP)) return null

        rest = rest.substringBefore('#').substringBefore('?')
        val host = rest.substringBefore('/')
        if (!HOST_PATTERN.matches(host)) return null

        // 瀏覽器複製來的 Odoo 頁面路徑（/web…、/odoo…）不是伺服器位址的一部分；
        // 其他路徑保留，以免破壞架在子路徑下的部署。
        val path = rest.substringAfter('/', missingDelimiterValue = "").trimEnd('/')
        val keepPath = path.isNotEmpty() && !path.startsWith("web") && !path.startsWith("odoo")
        return if (keepPath) "$HTTPS$host/$path" else "$HTTPS$host"
    }

    /** 給輸入欄位顯示用：去掉 scheme，因為欄位前面已經固定顯示 https://。 */
    fun displayValue(normalized: String): String = normalized.removePrefix(HTTPS)
}
