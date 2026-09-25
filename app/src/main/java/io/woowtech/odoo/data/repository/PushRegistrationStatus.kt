package io.woowtech.odoo.data.repository

/** Local observation only: even ACKNOWLEDGED does not prove notification delivery. No server text. */
enum class PushRegistrationStatus {
    NOT_CHECKED,
    REGISTERING,
    ACKNOWLEDGED,
    NOT_CONFIGURED,
    CONTRACT_REJECTED,
    RETRY_NEEDED,
    SIGN_IN_REQUIRED,
    UNREGISTERED,
}
