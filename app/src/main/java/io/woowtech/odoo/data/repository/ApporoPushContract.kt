package io.woowtech.odoo.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** Strict v2 boundary; never expose remote error bodies via exceptions or local status. */
internal object ApporoPushContract {
    const val BRAND = "apporo"
    const val CAPABILITIES_PATH = "/woow_fcm_push/capabilities"

    fun result(body: String): JsonObject = try {
        val root = JsonParser.parseString(body).asJsonObject
        if (root.has("error") && !root.get("error").isJsonNull) reject()
        val result = root.getAsJsonObject("result") ?: reject()
        if (result.has("error")) reject()
        result
    } catch (error: PushContractException) {
        throw error
    } catch (_: Exception) {
        reject()
    }

    fun requireCapabilities(body: String) {
        val valid = runCatching {
            val result = result(body)
            versionSupported(result) && result.getAsJsonArray("supported_brands").any {
                it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == BRAND
            }
        }.getOrDefault(false)
        if (!valid) throw PushContractException(PushRegistrationStatus.NOT_CONFIGURED)
    }

    fun requireRegistration(body: String) {
        val result = result(body)
        val brand = result.get("app_brand")
        if (!versionSupported(result) || brand == null || !brand.isJsonPrimitive ||
            !brand.asJsonPrimitive.isString || brand.asString != BRAND
        ) reject()
    }

    fun requireUnregistration(body: String) {
        val success = result(body).get("success") ?: reject()
        // false means no row existed: the cleanup operation still completed.
        if (!success.isJsonPrimitive || !success.asJsonPrimitive.isBoolean) reject()
    }

    private fun versionSupported(result: JsonObject): Boolean {
        val version = result.get("push_contract_version") ?: return false
        return version.isJsonPrimitive && version.asJsonPrimitive.isNumber &&
            runCatching { version.asBigDecimal.intValueExact() >= 2 }.getOrDefault(false)
    }

    private fun reject(): Nothing = throw PushContractException(PushRegistrationStatus.CONTRACT_REJECTED)
}

internal class PushContractException(val status: PushRegistrationStatus) : IOException(status.name)
