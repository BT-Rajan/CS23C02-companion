package app.cs23c02.companion.auth

import kotlinx.serialization.Serializable

/** [accessExpiresAtMs] is wall-clock millis; the client refreshes a little before it to avoid a wasted 401. */
@Serializable
data class Tokens(val access: String, val refresh: String, val accessExpiresAtMs: Long)

interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear()
}

class InMemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    @Synchronized override fun load() = tokens
    @Synchronized override fun save(tokens: Tokens) { this.tokens = tokens }
    @Synchronized override fun clear() { tokens = null }
}
