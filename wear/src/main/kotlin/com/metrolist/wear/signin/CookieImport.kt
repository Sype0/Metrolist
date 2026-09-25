/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.signin

import org.json.JSONArray

/**
 * Turns whatever the user pasted into a `Cookie` header for music.youtube.com. Accepts a raw
 * header (`SAPISID=...; ...`), a Netscape `cookies.txt`, or a JSON export from cookie extensions.
 */
object CookieImport {
    /** Returns the header, or null when the input holds no usable login (no `SAPISID`). */
    fun parse(input: String): String? {
        val text = input.trim().removePrefix("﻿")
        if (text.isEmpty()) return null
        val cookies =
            when {
                text.startsWith("[") -> runCatching { fromJson(text) }.getOrNull() ?: return null
                text.lineSequence().any { it.split('\t').size >= 7 } -> fromNetscape(text)
                else -> fromHeader(text)
            }
        val byName = LinkedHashMap<String, String>()
        cookies.forEach { (name, value) -> if (name.isNotEmpty()) byName[name] = value }
        if (byName["SAPISID"].isNullOrEmpty()) return null
        return byName.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun isYouTube(domain: String) = domain.trimStart('.').let { it == "youtube.com" || it.endsWith(".youtube.com") }

    private fun fromHeader(text: String): List<Pair<String, String>> =
        text
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .split(';')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }

    private fun fromNetscape(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { raw ->
            // curl marks HttpOnly cookies with this prefix instead of commenting them out.
            val line = raw.removePrefix("#HttpOnly_")
            if (line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t')
            if (fields.size < 7 || !isYouTube(fields[0])) null else fields[5] to fields[6].trim()
        }.toList()

    private fun fromJson(text: String): List<Pair<String, String>> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val cookie = array.optJSONObject(i) ?: return@mapNotNull null
            if (!isYouTube(cookie.optString("domain"))) null else cookie.optString("name") to cookie.optString("value")
        }
    }
}
