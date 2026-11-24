package polaris.engine

import java.net.URI

/**
 * Where alerts are kept and checked (`worker/`): an address and the token that server expects.
 * The one built in at build time, or one entered in settings. They go as a pair: a token is only
 * ever sent to the address it was given with. Ported from Polaris/Services/AlertServer.swift
 * (the address and pairing rules only; talking to the server is a later phase).
 */
data class AlertServer(val url: String, val token: String, val isBuiltIn: Boolean) {
    enum class Problem(val message: String) {
        NotAnAddress("That isn't a web address."),
        NotHttps("The address must start with https:// (http only for localhost, while developing)."),
        HasCredentials("Put the token in its own field, not in the address."),
    }

    class InvalidAddress(val problem: Problem) : Exception(problem.message)

    companion object {
        /** Where the entered server is kept: the same accounts as the iOS Keychain's. */
        const val URL_ACCOUNT = "alerts.server.url"
        const val TOKEN_ACCOUNT = "alerts.server.token"

        /**
         * An address someone typed or built in: https, or http for a server on this computer
         * during development. No user, password, query or fragment; a trailing slash is dropped,
         * and so is the default port.
         */
        fun address(text: String): String {
            var trimmed = text.trim()
            while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw InvalidAddress(Problem.NotAnAddress)
            val scheme = uri.scheme?.lowercase() ?: throw InvalidAddress(Problem.NotAnAddress)
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: throw InvalidAddress(Problem.NotAnAddress)
            if (uri.rawQuery != null || uri.rawFragment != null) throw InvalidAddress(Problem.NotAnAddress)
            if (uri.rawUserInfo != null) throw InvalidAddress(Problem.HasCredentials)
            when {
                scheme == "https" -> {}
                scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1", "[::1]") -> {}
                scheme == "http" -> throw InvalidAddress(Problem.NotHttps)
                else -> throw InvalidAddress(Problem.NotAnAddress)
            }
            val port = uri.port.takeUnless { it == -1 || it == (if (scheme == "https") 443 else 80) }
            return buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(":").append(port)
                append(uri.rawPath ?: "")
            }
        }

        /**
         * The server to use: one entered in settings when there is one, else the built-in one.
         * Null (alerts off) when neither is complete or the built-in address doesn't pass. An
         * entered address never borrows the built-in token.
         */
        fun resolve(enteredUrl: String, enteredToken: String, builtInUrl: String?, builtInToken: String?): AlertServer? {
            if (enteredUrl.isNotBlank()) {
                val url = runCatching { address(enteredUrl) }.getOrNull() ?: return null
                if (enteredToken.isBlank()) return null
                return AlertServer(url, enteredToken.trim(), isBuiltIn = false)
            }
            val url = runCatching { address(builtInUrl?.trim() ?: "") }.getOrNull() ?: return null
            if (builtInToken.isNullOrBlank()) return null
            return AlertServer(url, builtInToken.trim(), isBuiltIn = true)
        }
    }
}
