package polaris.engine

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.IDN

/**
 * Where alerts are kept and checked (`worker/`): an address and the token that server expects.
 * The one built in at build time, or one entered in settings. They go as a pair: a token is only
 * ever sent to the address it was given with. Ported from Polaris/Services/AlertServer.swift
 * (the address and pairing rules only; talking to the server is a later phase).
 */
data class AlertServer(val url: String, val token: String, val isBuiltIn: Boolean) {
    enum class Problem(val message: String) {
        // Named as Swift's `AlertServer.Problem` cases, which the shared fixture records.
        notAnAddress("That isn't a web address."),
        notHTTPS("The address must start with https:// (http only for localhost, while developing)."),
        hasCredentials("Put the token in its own field, not in the address."),
    }

    class InvalidAddress(val problem: Problem) : Exception(problem.message)

    /** The token never reaches a log through `toString`. */
    override fun toString() = "AlertServer(url=$url, token=redacted, isBuiltIn=$isBuiltIn)"

    companion object {
        /** The secret account holding the entered server: address and token as one record. */
        const val ACCOUNT = "alerts.server"

        fun encodeRecord(url: String, token: String): String = buildJsonObject {
            put("url", url)
            put("token", token)
        }.toString()

        fun decodeRecord(text: String): Pair<String, String>? = runCatching {
            val o = HTTP.json.parseToJsonElement(text).jsonObject
            val url = o["url"]?.jsonPrimitive?.contentOrNull
            val token = o["token"]?.jsonPrimitive?.contentOrNull
            if (url.isNullOrBlank() || token.isNullOrBlank()) null else url to token
        }.getOrNull()

        /**
         * An address someone typed or built in: https, or http for a server on this computer
         * during development. No user, password, query or fragment; trailing slashes and the
         * default port are dropped; the path is kept as typed.
         *
         * This follows what iOS's `URLComponents(string:)` accepts and how `URL` spells it, case by
         * case in `PolarisTests/Fixtures/alert-server-addresses.json` (generated from the Swift):
         * underscores in host names pass, a non-ASCII host is written in punycode, characters a
         * path can't hold are percent-encoded, a space in the host fails, any number is a port.
         */
        fun address(text: String): String {
            var trimmed = text.trim()
            while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
            val parts = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$").matchEntire(trimmed)
                ?: throw InvalidAddress(Problem.notAnAddress)
            val scheme = parts.groupValues[1].lowercase()
            val authority = parts.groupValues[2]
            val rawPath = parts.groupValues[3]
            if (parts.groups[4] != null || parts.groups[5] != null) throw InvalidAddress(Problem.notAnAddress)

            val at = authority.lastIndexOf('@')
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val (rawHost, port) = splitHostPort(hostPort) ?: throw InvalidAddress(Problem.notAnAddress)
            val host = asciiHost(rawHost) ?: throw InvalidAddress(Problem.notAnAddress)
            if (host.isEmpty()) throw InvalidAddress(Problem.notAnAddress)
            if (at >= 0) throw InvalidAddress(Problem.hasCredentials)
            when {
                scheme == "https" -> {}
                scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1", "[::1]") -> {}
                scheme == "http" -> throw InvalidAddress(Problem.notHTTPS)
                else -> throw InvalidAddress(Problem.notAnAddress)
            }
            val keptPort = port?.takeUnless { it == java.math.BigInteger.valueOf(if (scheme == "https") 443L else 80L) }
            return buildString {
                append(scheme).append("://").append(host)
                if (keptPort != null) append(":").append(keptPort)
                append(encodePath(rawPath))
            }
        }

        /** Host and port: `[v6]:port` or `name:port`; an empty port is none; a port is any run of digits, as iOS takes it. */
        private fun splitHostPort(text: String): Pair<String, java.math.BigInteger?>? {
            val (host, portText) = if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                val rest = text.substring(close + 1)
                if (rest.isNotEmpty() && !rest.startsWith(":")) return null
                text.substring(0, close + 1) to rest.removePrefix(":").takeIf { rest.isNotEmpty() }
            } else {
                val colon = text.lastIndexOf(':')
                if (colon < 0) text to null else text.substring(0, colon) to text.substring(colon + 1)
            }
            if (portText.isNullOrEmpty()) return host to null
            if (!portText.all { it in '0'..'9' }) return null
            return host to java.math.BigInteger(portText)
        }

        /** Lower case; punycode for non-ASCII; null for characters a host can't hold (a space, `<`). */
        private fun asciiHost(host: String): String? {
            if (host.startsWith("[")) return host.lowercase()
            if (host.any { it.isWhitespace() || it in "<>\"{}|\\^`" }) return null
            val ascii = if (host.all { it.code < 128 }) host else runCatching { IDN.toASCII(host) }.getOrNull() ?: return null
            return ascii.lowercase()
        }

        private const val PATH_SAFE = "-._~!$&'()*+,;=:@/%"

        /**
         * Percent-encodes, as UTF-8, what a URL path can't hold. A `%` that starts a valid escape
         * is kept; one that doesn't is itself encoded (`/%zz` becomes `/%25zz`), as iOS does.
         */
        private fun encodePath(path: String): String = buildString {
            val bytes = path.encodeToByteArray()
            fun hex(i: Int) = i < bytes.size && (bytes[i].toInt().toChar().let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            for ((i, byte) in bytes.withIndex()) {
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c == '%' && hex(i + 1) && hex(i + 2) -> append(c)
                    c.code < 128 && c != '%' && (c.isLetterOrDigit() || c in PATH_SAFE) -> append(c)
                    else -> append("%").append("%02X".format(byte.toInt() and 0xFF))
                }
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
