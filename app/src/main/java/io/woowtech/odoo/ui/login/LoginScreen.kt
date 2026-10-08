package io.woowtech.odoo.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.woowtech.odoo.R
import io.woowtech.odoo.ui.theme.WoowFixedBrandTheme
import io.woowtech.odoo.ui.theme.brandSolidButtonColors

/**
 * 登入表單在大螢幕上的寬度上限，對齊 iOS `LoginView` 的
 * `.frame(maxWidth: 500)`。平板上不讓欄位橫向拉滿整個螢幕。
 */
private val FORM_MAX_WIDTH = 500.dp

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit,
    // Re-login after an unrecoverable session expiry: start on the password step of the active account.
    prefillActiveAccount: Boolean = false,
) {
    WoowFixedBrandTheme {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (prefillActiveAccount) {
        LaunchedEffect(viewModel) { viewModel.prefillFromActiveAccount() }
    }

    // 系統返回（手勢／KEYCODE_BACK）在帳密步驟＝左上角返回鍵：回伺服器步驟並保留網址與 DB。
    // 伺服器步驟不攔截，交還給 NavHost／Activity（離開）。
    BackHandler(enabled = uiState.step == LoginStep.CREDENTIALS) {
        viewModel.goBack()
    }

    // 與 iOS LoginView 對齊：純色背景，不用品牌色漸層。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // MainActivity 開了 enableEdgeToEdge()。其他畫面靠 Material3 TopAppBar／Scaffold 自動
        // 避開系統列，登入頁沒有 TopAppBar，要自己吃 safeDrawing（狀態列、導覽列、瀏海、鍵盤），
        // 否則帳密步驟的返回鍵會被畫在狀態列底下而點不到。背景仍鋪滿到螢幕邊緣（在外層 Box）。
        // safeDrawing 已包含 ime，所以取代原本的 imePadding()。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Back button for credentials step
            if (uiState.step == LoginStep.CREDENTIALS) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_button),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(48.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Flavor overlay selects the brand mark at every density.
            Image(
                painter = painterResource(id = R.drawable.woow_logo),
                contentDescription = stringResource(R.string.content_description_logo),
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 不用 Card：Material 3 的 Card 會依 elevation 疊上一層 surfaceTint
            // （tint 來自 colorScheme），錯誤態時整張卡片會變色。iOS 也沒有卡片。
            Column(
                modifier = Modifier
                    // 與 iOS `.frame(maxWidth: 500)` 對齊：平板／大螢幕上限寬置中，
                    // 不讓表單橫向拉滿。順序重要 —— widthIn 必須在 fillMaxWidth 之前，
                    // 否則 fillMaxWidth 會把 minWidth 撐到父層寬度而使上限失效。
                    .widthIn(max = FORM_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                AnimatedContent(
                    targetState = uiState.step,
                    transitionSpec = {
                        if (targetState == LoginStep.CREDENTIALS) {
                            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                        } else {
                            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                        }
                    },
                    label = "login_step"
                ) { step ->
                    when (step) {
                        LoginStep.SERVER_INFO -> ServerInfoForm(
                            serverUrl = uiState.serverUrl,
                            database = uiState.database,
                            serverUrlError = uiState.serverUrlError,
                            databaseError = uiState.databaseError,
                            onServerUrlChange = viewModel::updateServerUrl,
                            onDatabaseChange = viewModel::updateDatabase,
                            onNextClick = viewModel::goToNextStep
                        )
                        LoginStep.CREDENTIALS -> CredentialsForm(
                            username = uiState.username,
                            password = uiState.password,
                            rememberMe = uiState.rememberMe,
                            usernameError = uiState.usernameError,
                            passwordError = uiState.passwordError,
                            isLoading = uiState.isLoading,
                            onUsernameChange = viewModel::updateUsername,
                            onPasswordChange = viewModel::updatePassword,
                            onRememberMeChange = viewModel::updateRememberMe,
                            onLoginClick = { viewModel.login(onLoginSuccess) }
                        )
                    }
                }
            }

            // Error message
            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .widthIn(max = FORM_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.errorMessage(fallback = error),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}

@Composable
private fun ServerInfoForm(
    serverUrl: String,
    database: String,
    serverUrlError: LoginFieldError?,
    databaseError: LoginFieldError?,
    onServerUrlChange: (String) -> Unit,
    onDatabaseChange: (String) -> Unit,
    onNextClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Server URL
        StyledTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = stringResource(R.string.server_url),
            placeholder = stringResource(R.string.server_url_hint),
            // W2-4 U4: a typed/pasted scheme replaces the fixed prefix (no "https://http://…").
            prefix = stringResource(R.string.https_prefix).takeIf { showsHttpsPrefix(serverUrl) },
            isError = serverUrlError != null,
            errorMessage = serverUrlError?.let { stringResource(it.messageResource()) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Database
        StyledTextField(
            value = database,
            onValueChange = onDatabaseChange,
            label = stringResource(R.string.database_name),
            placeholder = stringResource(R.string.database_name_hint),
            isError = databaseError != null,
            errorMessage = databaseError?.let { stringResource(it.messageResource()) },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onNextClick()
                }
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onNextClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = brandSolidButtonColors()
        ) {
            Text(
                text = stringResource(R.string.next_button),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun CredentialsForm(
    username: String,
    password: String,
    rememberMe: Boolean,
    usernameError: LoginFieldError?,
    passwordError: LoginFieldError?,
    isLoading: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberMeChange: (Boolean) -> Unit,
    onLoginClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Username
        StyledTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = stringResource(R.string.username),
            placeholder = stringResource(R.string.username_hint),
            isError = usernameError != null,
            errorMessage = usernameError?.let { stringResource(it.messageResource()) },
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Password
        StyledTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.password),
            placeholder = stringResource(R.string.password_hint),
            isError = passwordError != null,
            errorMessage = passwordError?.let { stringResource(it.messageResource()) },
            enabled = !isLoading,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (passwordVisible) {
                            stringResource(R.string.content_description_visibility_off)
                        } else {
                            stringResource(R.string.content_description_visibility_on)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    onLoginClick()
                }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Remember me
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = rememberMe,
                onCheckedChange = onRememberMeChange,
                enabled = !isLoading,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onLoginClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading,
            colors = brandSolidButtonColors()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.login_button),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    prefix: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    // 與 iOS LoginView 對齊：標籤放在欄位「上方」，欄位本身是無邊框的填色圓角矩形。
    // 不用 OutlinedTextField 的浮動標籤與前置圖示 —— iOS 兩者都沒有。
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            },
            prefix = prefix?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            trailingIcon = trailingIcon,
            isError = isError,
            singleLine = true,
            enabled = enabled,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                // 填色固定用 surfaceVariant：不隨 elevation 疊 surfaceTint，
                // 也不在錯誤態整塊變色（錯誤只靠標籤與下方訊息表示）。
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                // 無底線：iOS 的欄位沒有任何邊框或底線
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
        )

        if (isError && errorMessage != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * Whether the server field shows its fixed `https://` prefix (W2-4 U4). Once the field itself starts with
 * `http://` or `https://` (any case, leading spaces ignored) the prefix is hidden, so "http://host" no
 * longer reads "https://http://host". Validation is unchanged: http:// is still refused (HTTPS required).
 */
internal fun showsHttpsPrefix(fieldValue: String): Boolean {
    val value = fieldValue.trimStart().lowercase()
    return !value.startsWith("http://") && !value.startsWith("https://")
}
