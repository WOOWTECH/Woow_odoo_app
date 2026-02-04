package io.woowtech.odoo.ui.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.woowtech.odoo.data.repository.AccountRepository
import io.woowtech.odoo.domain.model.OdooAccount
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount
}
