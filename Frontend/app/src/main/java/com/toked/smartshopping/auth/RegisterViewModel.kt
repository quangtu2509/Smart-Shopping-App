package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class AuthPage { STARTING, REGISTER, LOGIN_ENTRY, ONBOARDING, HOME_ENTRY, SESSIONS, VERIFY_EMAIL, VERIFY_RESET, FORGOT, RESET_PASSWORD, LINK_GOOGLE, REGISTER_SUCCESS }
data class RegisterUiState(
    val page: AuthPage = AuthPage.STARTING,
    val email: String = "", val password: String = "", val confirmation: String = "",
    val errors: Map<String, String> = emptyMap(), val message: String? = null,
    val sending: Boolean = false, val retrySeconds: Int = 0,
    val account: AccountProfile? = null, val savingSession: Boolean = false,
    val challenge: AuthChallenge? = null, val code: String = "", val resendSeconds: Int = 0,
    val sessions: List<AuthSessionSummary> = emptyList(), val nextCursor: String? = null,
) { override fun toString() = "RegisterUiState(page=$page, sending=$sending)" }

sealed interface AuthUiEvent {
    data class Notice(val text: String) : AuthUiEvent
    data object ClearGoogle : AuthUiEvent
}

class RegisterViewModel(private val api: AuthGateway, private val store: SessionStore) : ViewModel() {
    private val mutableState = MutableStateFlow(RegisterUiState())
    val state = mutableState.asStateFlow()
    private val eventsChannel = Channel<AuthUiEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private var resendCountdown: Job? = null
    private var resetToken: String? = null
    private var pendingDestination = AuthPage.HOME_ENTRY
    private var initialized = false
    private var pendingSession: AuthSession? = null
    private val repository = AuthSessionRepository(api, store)
    private var restoreInFlight = false
    private var countdown: Job? = null
    private var pendingLogout: Boolean? = null

    fun start() {
        if (initialized) return
        initialized = true
        restore()
    }

    private fun restore(destination: AuthPage = AuthPage.HOME_ENTRY) {
        if (restoreInFlight) return
        restoreInFlight = true
        mutableState.value = RegisterUiState(page = AuthPage.STARTING)
        viewModelScope.launch {
            try {
                val saved = repository.restore()
                mutableState.value = if (saved == null) RegisterUiState(page = AuthPage.LOGIN_ENTRY)
                    else RegisterUiState(page = destination, account = saved.account)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthFailure.SessionEnded) { expired() }
            catch (_: Exception) { restoreFailed() }
            finally { restoreInFlight = false }
        }
    }

    private fun restoreFailed() {
        mutableState.value = RegisterUiState(message = "Chưa thể khôi phục phiên. Kiểm tra kết nối và thử lại.")
    }
    fun retryRestore() {
        if (state.value.page != AuthPage.STARTING) return
        val all = pendingLogout
        if (all != null) logout(all) else restore()
    }
    fun onResume() {
        if (!state.value.sending && state.value.page in setOf(AuthPage.HOME_ENTRY, AuthPage.ONBOARDING, AuthPage.SESSIONS)) {
            restore(if (state.value.page == AuthPage.ONBOARDING) AuthPage.ONBOARDING else AuthPage.HOME_ENTRY)
        }
    }

    fun edit(field: String, value: String) {
        val current = state.value
        if (current.sending || current.savingSession || current.page !in setOf(AuthPage.REGISTER, AuthPage.LOGIN_ENTRY, AuthPage.FORGOT, AuthPage.VERIFY_EMAIL, AuthPage.VERIFY_RESET, AuthPage.RESET_PASSWORD, AuthPage.LINK_GOOGLE)) return
        mutableState.value = when (field) {
            "code" -> current.copy(code = value.filter(Char::isDigit).take(6), message = null)
            "email" -> current.copy(email = value, errors = current.errors - field, message = null)
            "password" -> current.copy(password = value, errors = current.errors - field, message = null)
            "confirm_password" -> current.copy(confirmation = value, errors = current.errors - field, message = null)
            else -> current
        }
    }

    fun submit() {
        if (state.value.page in setOf(AuthPage.FORGOT, AuthPage.VERIFY_EMAIL, AuthPage.VERIFY_RESET, AuthPage.RESET_PASSWORD, AuthPage.LINK_GOOGLE)) {
            submitStep()
            return
        }
        val current = state.value
        if (current.sending || current.retrySeconds > 0 || current.page !in setOf(AuthPage.REGISTER, AuthPage.LOGIN_ENTRY)) return
        val login = current.page == AuthPage.LOGIN_ENTRY
        val errors = if (pendingSession != null) emptyMap() else if (login) validateLogin(current) else validate(current)
        if (errors.isNotEmpty()) { mutableState.value = current.copy(errors = errors); return }
        mutableState.value = current.copy(sending = true, errors = emptyMap(), message = null)
        viewModelScope.launch {
            try {
                val session = pendingSession ?: (if (login) api.login(current.email, current.password)
                    else api.register(current.email, current.password, current.confirmation))
                saveAuthenticated(session, if (login) AuthPage.HOME_ENTRY else AuthPage.REGISTER_SUCCESS)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (step: AuthFailure.NextStep) { showChallenge(step.challenge) }
            catch (failure: AuthFailure.Http) { handleHttp(failure) }
            catch (_: AuthFailure.Network) { failed(if (login) "Chưa xác nhận được kết quả đăng nhập. Kiểm tra kết nối trước khi thử lại." else "Chưa xác nhận được kết quả đăng ký. Kiểm tra kết nối và thử đăng nhập trước khi gửi lại.") }
            catch (_: AuthFailure.InvalidResponse) { failed(if (login) "Chưa xác nhận được kết quả đăng nhập. Vui lòng thử lại sau." else "Chưa xác nhận được kết quả đăng ký. Hãy thử đăng nhập trước khi gửi lại.") }
            catch (_: AuthFailure.Unconfigured) { failed("Hiện chưa thể kết nối dịch vụ. Vui lòng thử lại sau.") }
            catch (_: Exception) { failed("Chưa xử lý được yêu cầu. Vui lòng thử lại sau.") }
        }
    }

    private suspend fun saveAuthenticated(value: AuthSession, destination: AuthPage) {
        if (pendingSession == null) pendingDestination = destination
        pendingSession = value
        try { repository.save(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.value = state.value.copy(sending = false, password = "", confirmation = "", code = "", savingSession = true,
                message = "Đã xác thực nhưng chưa lưu được phiên. Hãy thử lưu lại.")
            return
        }
        pendingSession = null
        countdown?.cancel()
        resendCountdown?.cancel()
        resetToken = null
        mutableState.value = RegisterUiState(page = pendingDestination, account = value.account)
        if (pendingDestination == AuthPage.HOME_ENTRY) eventsChannel.send(AuthUiEvent.Notice("Đăng nhập thành công"))
    }

    private fun showChallenge(challenge: AuthChallenge) {
        val page = when (challenge.nextAction) {
            "VERIFY_EMAIL" -> AuthPage.VERIFY_EMAIL
            "VERIFY_RESET" -> AuthPage.VERIFY_RESET
            "CONFIRM_PASSWORD" -> AuthPage.LINK_GOOGLE
            else -> { failed("Bước xác thực không hợp lệ."); return }
        }
        mutableState.value = RegisterUiState(page = page, email = state.value.email, challenge = challenge,
            message = if (page == AuthPage.VERIFY_RESET) "Nếu tài khoản hỗ trợ khôi phục, mã đã được gửi đến email của bạn. Tài khoản chỉ dùng Google hãy tiếp tục với Google." else null)
        startResendCountdown(challenge.resendAfter)
    }
    private fun startResendCountdown(seconds: Int) {
        resendCountdown?.cancel()
        mutableState.value = state.value.copy(resendSeconds = seconds)
        resendCountdown = viewModelScope.launch {
            while (state.value.resendSeconds > 0) {
                delay(1000)
                mutableState.value = state.value.copy(resendSeconds = maxOf(0,state.value.resendSeconds - 1))
            }
        }
    }
    fun showForgot() {
        if (state.value.sending || state.value.account != null) return
        pendingSession = null
        resetToken = null
        mutableState.value = RegisterUiState(page = AuthPage.FORGOT,email = state.value.email,retrySeconds = state.value.retrySeconds)
    }
    fun continueAfterRegistration() {
        if (state.value.page == AuthPage.REGISTER_SUCCESS) mutableState.value = state.value.copy(page = AuthPage.ONBOARDING)
    }
    fun resend() {
        val current = state.value
        val challenge = current.challenge ?: return
        if (current.sending || current.resendSeconds > 0 || current.retrySeconds > 0 || current.savingSession) return
        mutableState.value = current.copy(sending = true,message = null)
        viewModelScope.launch {
            try {
                val updated = api.resend(challenge.id)
                mutableState.value = state.value.copy(challenge = updated,code = "",sending = false,message = "Yêu cầu gửi lại đã được xử lý. Kiểm tra cả thư rác.")
                startResendCountdown(updated.resendAfter)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure.Http) { handleHttp(failure) }
            catch (_: Exception) { failed("Chưa gửi lại được mã. Vui lòng thử lại.") }
        }
    }
    private fun submitStep() {
        val current = state.value
        if (current.sending || current.retrySeconds > 0) return
        if (!current.savingSession) {
            val errors = when (current.page) {
                AuthPage.FORGOT -> validateLogin(current.copy(password = "placeholder"))
                AuthPage.RESET_PASSWORD -> buildMap {
                    if (!validPassword(current.password)) put("password",passwordHint)
                    if (current.password != current.confirmation) put("confirm_password","Mật khẩu xác nhận chưa khớp.")
                }
                AuthPage.LINK_GOOGLE -> if (current.password.isEmpty()) mapOf("password" to "Nhập mật khẩu Smart Shopping hiện tại.") else emptyMap()
                else -> if (current.code.length != 6) mapOf("code" to "Nhập mã gồm 6 chữ số.") else emptyMap()
            }
            if (errors.isNotEmpty()) { mutableState.value = current.copy(errors = errors); return }
        }
        mutableState.value = current.copy(sending = true,message = null,errors = emptyMap())
        viewModelScope.launch {
            try {
                val saved = pendingSession
                if (saved != null) { saveAuthenticated(saved,pendingDestination); return@launch }
                when (current.page) {
                    AuthPage.FORGOT -> showChallenge(api.forgot(current.email))
                    AuthPage.VERIFY_EMAIL -> {
                        val result = api.verifyEmail(current.challenge!!.id,current.code)
                        saveAuthenticated(result,if (result.isNewUser) AuthPage.REGISTER_SUCCESS else AuthPage.HOME_ENTRY)
                    }
                    AuthPage.VERIFY_RESET -> {
                        resetToken = api.verifyReset(current.challenge!!.id,current.code)
                        resendCountdown?.cancel()
                        mutableState.value = RegisterUiState(page = AuthPage.RESET_PASSWORD,email = current.email)
                    }
                    AuthPage.RESET_PASSWORD -> {
                        api.reset(resetToken ?: throw AuthFailure.InvalidResponse(),current.password,current.confirmation)
                        resetToken = null
                        mutableState.value = RegisterUiState(page = AuthPage.LOGIN_ENTRY,email = current.email,
                            message = "Đặt lại mật khẩu thành công. Vui lòng đăng nhập.")
                        eventsChannel.send(AuthUiEvent.Notice("Đặt lại mật khẩu thành công"))
                    }
                    AuthPage.LINK_GOOGLE -> saveAuthenticated(api.linkGoogle(current.challenge!!.id,current.password),AuthPage.HOME_ENTRY)
                    else -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AuthFailure.Http) {
                if (failure.code == "CHALLENGE_INVALID") failed("Mã sai, hết hạn hoặc đã dùng quá số lần cho phép. Hãy gửi lại mã hoặc bắt đầu lại.")
                else if (failure.status == 401) failed("Mật khẩu Smart Shopping không đúng.")
                else handleHttp(failure)
            } catch (_: Exception) { failed("Chưa hoàn tất được yêu cầu. Kiểm tra kết nối và thử lại.") }
        }
    }
    suspend fun signInGoogle(getToken: suspend (String) -> String) {
        val current = state.value
        if (current.sending || current.savingSession || current.retrySeconds > 0 || current.page !in setOf(AuthPage.LOGIN_ENTRY,AuthPage.REGISTER)) return
        mutableState.value = current.copy(sending = true,message = null)
        try {
            val challenge = api.googleChallenge()
            val token = getToken(challenge.nonce ?: throw AuthFailure.InvalidResponse())
            val result = api.google(challenge.id,token)
            saveAuthenticated(result,if (result.isNewUser) AuthPage.REGISTER_SUCCESS else AuthPage.HOME_ENTRY)
        } catch (cancelled: CancellationException) {
            mutableState.value = state.value.copy(sending = false)
            throw cancelled
        } catch (_: AuthFailure.GoogleCancelled) { mutableState.value = current.copy(sending = false) }
        catch (step: AuthFailure.NextStep) { showChallenge(step.challenge) }
        catch (failure: AuthFailure.Http) { handleHttp(failure) }
        catch (_: Exception) { failed("Chưa đăng nhập được bằng Google. Kiểm tra tài khoản và kết nối rồi thử lại.") }
    }

    fun showLogin() {
        if (state.value.sending || state.value.account != null) return
        pendingSession = null
        resetToken = null
        resendCountdown?.cancel()
        mutableState.value = RegisterUiState(page = AuthPage.LOGIN_ENTRY, email = state.value.email, retrySeconds = state.value.retrySeconds)
    }
    fun showRegister() {
        if (state.value.sending || state.value.account != null) return
        pendingSession = null
        mutableState.value = RegisterUiState(page = AuthPage.REGISTER, email = state.value.email, retrySeconds = state.value.retrySeconds)
    }
    fun skipOnboarding() { if (state.value.page == AuthPage.ONBOARDING) mutableState.value = state.value.copy(page = AuthPage.HOME_ENTRY) }
    fun showProfile() { if (state.value.account != null) mutableState.value = state.value.copy(page = AuthPage.ONBOARDING) }
    fun home() { if (!state.value.sending && state.value.account != null) mutableState.value = state.value.copy(page = AuthPage.HOME_ENTRY, sessions = emptyList(), nextCursor = null, message = null) }

    fun showSessions(more: Boolean = false) {
        if (state.value.sending || state.value.account == null) return
        val cursor = if (more) state.value.nextCursor ?: return else null
        val previous = if (more) state.value.sessions else emptyList()
        mutableState.value = state.value.copy(page = AuthPage.SESSIONS, sending = true, message = null)
        viewModelScope.launch {
            try {
                val page = repository.sessions(cursor)
                mutableState.value = state.value.copy(sending = false, sessions = (previous + page.items).distinctBy { it.id }, nextCursor = page.nextCursor)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthFailure.SessionEnded) { expired() }
            catch (_: Exception) { failed("Chưa tải được phiên đăng nhập. Kiểm tra kết nối và thử lại.") }
        }
    }

    fun revokeSession(id: String) {
        if (state.value.sending || state.value.page != AuthPage.SESSIONS || state.value.sessions.none { it.id == id }) return
        mutableState.value = state.value.copy(sending = true, message = null)
        viewModelScope.launch {
            try {
                if (repository.revoke(id)) expired("Phiên trên thiết bị này đã được thu hồi.")
                else {
                    mutableState.value = state.value.copy(sending = false)
                    showSessions()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthFailure.SessionEnded) { expired() }
            catch (_: Exception) { failed("Chưa xác nhận được việc thu hồi phiên. Tải lại danh sách để kiểm tra.") }
        }
    }

    fun logout(all: Boolean = false) {
        if (state.value.sending) return
        eventsChannel.trySend(AuthUiEvent.ClearGoogle)
        resetToken = null
        resendCountdown?.cancel()
        pendingLogout = all
        pendingSession = null
        countdown?.cancel()
        mutableState.value = RegisterUiState(page = AuthPage.STARTING, sending = true)
        viewModelScope.launch {
            val result = repository.logout(all)
            if (result.localCleared) pendingLogout = null
            mutableState.value = RegisterUiState(page = if (result.localCleared) AuthPage.LOGIN_ENTRY else AuthPage.STARTING,
                message = when {
                    !result.localCleared -> "Chưa xóa được phiên trên thiết bị. Hãy thử đăng xuất lại."
                    !result.serverRevoked -> "Đã đăng xuất trên thiết bị; chưa xác nhận thu hồi phiên trên máy chủ."
                    else -> "Đã đăng xuất."
                })
        }
    }

    private fun expired(message: String = "Phiên đăng nhập đã hết hạn hoặc bị thu hồi. Vui lòng đăng nhập lại.") {
        pendingSession = null
        mutableState.value = RegisterUiState(page = AuthPage.LOGIN_ENTRY, message = message)
    }

    private fun handleHttp(failure: AuthFailure.Http) {
        val fields = failure.fields.mapValues { (field, _) -> fieldMessage(field) }
        when {
            state.value.page == AuthPage.LOGIN_ENTRY && failure.status == 401 -> failed("Email hoặc mật khẩu không đúng.")
            failure.code == "ACCOUNT_LOCKED" -> {
                val seconds = failure.retryAfter
                if (seconds != null) cooldown(seconds, "Tài khoản đang bị khóa tạm thời. Vui lòng chờ rồi thử lại.")
                else failed("Tài khoản đang bị khóa. Vui lòng liên hệ hỗ trợ hoặc thử lại sau.")
            }
            failure.code == "EMAIL_ALREADY_EXISTS" -> mutableState.value = state.value.copy(sending = false,
                errors = mapOf("email" to "Email đã được sử dụng. Đăng nhập hoặc dùng email khác."))
            failure.code == "CHALLENGE_INVALID" -> failed("Mã hoặc yêu cầu đã hết hạn hoặc dùng hết số lần thử. Vui lòng bắt đầu lại.")
            failure.code == "EMAIL_DELIVERY_UNAVAILABLE" -> failed("Chưa gửi được email. Vui lòng thử lại sau.")
            failure.status == 422 -> mutableState.value = state.value.copy(sending = false, errors = fields,
                message = if (fields.isEmpty()) "Kiểm tra lại thông tin đăng ký." else null)
            failure.status == 429 -> cooldown(failure.retryAfter ?: 60, "Bạn đã gửi quá nhiều yêu cầu. Vui lòng chờ rồi thử lại.")
            else -> failed("Dịch vụ chưa xử lý được yêu cầu. Vui lòng thử lại sau.")
        }
    }
    private fun cooldown(seconds: Int, message: String) {
        countdown?.cancel()
        mutableState.value = state.value.copy(sending = false, retrySeconds = seconds, message = message)
        countdown = viewModelScope.launch {
            while (state.value.retrySeconds > 0) {
                delay(1000)
                mutableState.value = state.value.copy(retrySeconds = maxOf(0, state.value.retrySeconds - 1))
            }
        }
    }
    private fun failed(message: String) { mutableState.value = state.value.copy(sending = false, message = message) }
    private fun fieldMessage(field: String) = when (field) {
        "email" -> "Nhập email hợp lệ."
        "password" -> passwordHint
        "confirm_password" -> "Mật khẩu xác nhận chưa khớp."
        else -> "Kiểm tra lại thông tin."
    }
}

internal fun validateLogin(state: RegisterUiState): Map<String, String> = buildMap {
    if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(state.email.trim()) || state.email.trim().length > 254)
        put("email", "Nhập email hợp lệ.")
    if (state.password.codePointCount(0, state.password.length) !in 1..128)
        put("password", "Nhập mật khẩu từ 1 đến 128 ký tự.")
}

internal fun validate(state: RegisterUiState): Map<String, String> = buildMap {
    if (state.email.trim().substringAfter('@').lowercase() != "gmail.com" || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(state.email.trim()) || state.email.trim().length > 254)
        put("email", "Dùng địa chỉ Gmail của bạn (@gmail.com).")
    if (!validPassword(state.password))
        put("password", passwordHint)
    if (state.confirmation != state.password) put("confirm_password", "Mật khẩu xác nhận chưa khớp.")
}

internal const val passwordHint = "Từ 10–128 ký tự, có chữ hoa, chữ thường và ký tự đặc biệt (không tính dấu cách)."
internal fun validPassword(value: String): Boolean {
    val points = value.codePoints().toArray()
    return points.size in 10..128 && points.any(Character::isUpperCase) && points.any(Character::isLowerCase) &&
        points.any { Character.getType(it) in Character.DASH_PUNCTUATION.toInt()..Character.FINAL_QUOTE_PUNCTUATION.toInt() }
}
