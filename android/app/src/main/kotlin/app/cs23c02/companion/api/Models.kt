package app.cs23c02.companion.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class Entitlement(
    @SerialName("edition_id") val editionId: String,
    val title: String,
    @SerialName("activated_at") val activatedAt: String,
)

@Serializable
data class Me(val email: String, val entitlements: List<Entitlement> = emptyList())

@Serializable
internal data class ErrorBody(val error: ErrorDetail)

@Serializable
internal data class ErrorDetail(val message: String, val code: String, val ref: String? = null, val fields: List<String>? = null)
