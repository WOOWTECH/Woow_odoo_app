package io.woowtech.odoo.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val login: String,
    val email: String? = null,
    val phone: String? = null,
    val mobile: String? = null,
    val website: String? = null,
    val function: String? = null, // Job title
    val imageBase64: String? = null
)
