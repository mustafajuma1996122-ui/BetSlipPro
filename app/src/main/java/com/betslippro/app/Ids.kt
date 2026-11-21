package com.betslippro.app

/** Stable 64-bit identifiers (FNV-1a). String.hashCode() is only 32 bits and collides far too easily. */
object Ids {
    fun stable(vararg parts: String): Long {
        var h = -3750763034362895579L // FNV-64 offset basis (0xcbf29ce484222325)
        for (b in parts.joinToString("|").toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L
        }
        return h and Long.MAX_VALUE
    }
}
