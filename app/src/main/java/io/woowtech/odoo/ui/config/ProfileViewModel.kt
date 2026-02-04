package io.woowtech.odoo.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
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
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false,
    val hasChanges: Boolean = false,
    private val originalProfile: UserProfile? = null
) {
    fun withProfile(profile: UserProfile) = copy(
        name = profile.name,
        email = profile.login,
        phone = profile.phone ?: "",
        mobile = profile.mobile ?: "",
        website = profile.website ?: "",
        jobTitle = profile.function ?: "",
        isLoading = false,
        originalProfile = profile
    )
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

            val profile = accountRepository.getUserProfile()
            if (profile != null) {
                _uiState.value = _uiState.value.withProfile(profile)
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
