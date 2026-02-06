package io.woowtech.odoo.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooLanguage
import io.woowtech.odoo.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val mobile: String = "",
    val website: String = "",
    val jobTitle: String = "",
    // v1.0.16: Odoo preferences
    val lang: String = "",
    val tz: String = "",
    val notificationType: String = "email",
    val signature: String = "",
    val availableLanguages: List<OdooLanguage> = emptyList(),
    // UI state
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
    val hasChanges: Boolean = false,
    private val originalProfile: UserProfile? = null
) {
    fun withProfile(profile: UserProfile, languages: List<OdooLanguage>) = copy(
        name = profile.name,
        email = profile.login,
        phone = profile.phone ?: "",
        mobile = profile.mobile ?: "",
        website = profile.website ?: "",
        jobTitle = profile.function ?: "",
        // v1.0.16: Odoo preferences
        lang = profile.lang ?: "",
        tz = profile.tz ?: "",
        notificationType = profile.notificationType ?: "email",
        signature = profile.signature ?: "",
        availableLanguages = languages,
        isLoading = false,
        originalProfile = profile
    )

    // v1.0.16: Helper to get language display name
    fun getLanguageDisplayName(): String {
        return availableLanguages.find { it.code == lang }?.name ?: lang.ifEmpty { "Not set" }
    }

    // v1.0.16: Helper to get notification type display name
    fun getNotificationDisplayName(): String {
        return when (notificationType) {
            "email" -> "Handle by Emails"
            "inbox" -> "Handle in Odoo"
            else -> notificationType
        }
    }
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // v1.0.16: Load profile and available languages in parallel
            val profile = accountRepository.getUserProfile()
            val languages = accountRepository.getAvailableLanguages()

            if (profile != null) {
                _uiState.value = _uiState.value.withProfile(profile, languages)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load profile"
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name, hasChanges = true)
    }

    fun updatePhone(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone, hasChanges = true)
    }

    fun updateMobile(mobile: String) {
        _uiState.value = _uiState.value.copy(mobile = mobile, hasChanges = true)
    }

    fun updateWebsite(website: String) {
        _uiState.value = _uiState.value.copy(website = website, hasChanges = true)
    }

    fun updateJobTitle(jobTitle: String) {
        _uiState.value = _uiState.value.copy(jobTitle = jobTitle, hasChanges = true)
    }

    // v1.0.16: Update language
    fun updateLanguage(lang: String) {
        _uiState.value = _uiState.value.copy(lang = lang, hasChanges = true)
    }

    // v1.0.16: Update timezone
    fun updateTimezone(tz: String) {
        _uiState.value = _uiState.value.copy(tz = tz, hasChanges = true)
    }

    // v1.0.16: Update notification type
    fun updateNotificationType(type: String) {
        _uiState.value = _uiState.value.copy(notificationType = type, hasChanges = true)
    }

    // v1.0.16: Update signature
    fun updateSignature(signature: String) {
        _uiState.value = _uiState.value.copy(signature = signature, hasChanges = true)
    }

    fun saveProfile() {
        val state = _uiState.value

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, error = null)

            val updates = mutableMapOf<String, Any>()

            state.name.takeIf { it.isNotBlank() }?.let { updates["name"] = it }
            updates["phone"] = state.phone.ifBlank { false }
            updates["mobile"] = state.mobile.ifBlank { false }
            updates["website"] = state.website.ifBlank { false }
            updates["function"] = state.jobTitle.ifBlank { false }

            // v1.0.16: Odoo preferences
            if (state.lang.isNotBlank()) {
                updates["lang"] = state.lang
            }
            if (state.tz.isNotBlank()) {
                updates["tz"] = state.tz
            }
            updates["notification_type"] = state.notificationType
            updates["signature"] = state.signature.ifBlank { false }

            val success = accountRepository.updateUserProfile(updates)

            if (success) {
                _uiState.value = state.copy(
                    isSaving = false,
                    saveSuccess = true,
                    hasChanges = false
                )
            } else {
                _uiState.value = state.copy(
                    isSaving = false,
                    error = "Failed to save profile"
                )
            }
        }
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }
}
