package com.toked.smartshopping.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject

@Serializable data class Meta(val request_id: String)
@Serializable data class Envelope<T>(val data: T, val meta: Meta)
@Serializable data class FieldError(val field: String, val code: String)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ApiError(
    val code: String,
    val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
    val retryable: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val field_errors: List<FieldError>? = null,
)
@Serializable data class ErrorEnvelope(val error: ApiError, val meta: Meta)

class ApiFailure(
    val status: Int,
    val code: String,
    val fields: List<FieldError>? = null,
    val retryAfter: Long? = null,
) : RuntimeException(code)

@Serializable data class UserDto(val id: String, val email: String, val status: String)
@Serializable data class ProfileDto(
    val version: Int = 1,
    val household_size: Int? = null,
    val default_period: String? = null,
    val preferred_category_ids: List<String> = emptyList(),
    val avoided_category_ids: List<String> = emptyList(),
    val preferred_brand_ids: List<String> = emptyList(),
    val avoided_brand_ids: List<String> = emptyList(),
    val max_stores: Int = 3,
    val optimization_mode: String = "BALANCED",
)
// Credentials are deliberately not data classes: their generated toString would expose tokens.
@Serializable class AuthTokens(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long,
    val refresh_token: String,
    val refresh_expires_at: String,
    val auth_session_id: String,
) { override fun toString() = "AuthTokens(REDACTED)" }
@Serializable class AuthResult(val user: UserDto, val profile: ProfileDto, val tokens: AuthTokens, val is_new_user: Boolean = false) {
    override fun toString() = "AuthResult(REDACTED)"
}
@Serializable class ChallengeDto(val challenge_id: String, val next_action: String,
    val expires_at: String, val resend_after_seconds: Long = 60, val nonce: String? = null) {
    override fun toString() = "ChallengeDto(REDACTED)"
}
@Serializable class ResetGrant(val reset_token: String) { override fun toString() = "ResetGrant(REDACTED)" }
@Serializable data class MeDto(val user: UserDto, val profile: ProfileDto)
@Serializable data class AuthSessionDto(
    val id: String, val created_at: String, val last_used_at: String,
    val expires_at: String, val is_current: Boolean, val device_name: String?,
)
@Serializable data class Page<T>(val items: List<T>, val next_cursor: String?)
