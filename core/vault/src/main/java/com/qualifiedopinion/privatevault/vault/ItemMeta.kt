package com.qualifiedopinion.privatevault.vault

import com.qualifiedopinion.privatevault.format.VaultFormatException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ItemKind { PHOTO, VIDEO }

/**
 * `.vmeta` plaintext, JSON v1 (planning pack §3.5). Only ever stored encrypted.
 *
 * [toString] leaves out the name, hash and id so the object can't leak them into a log by accident.
 */
@Serializable
data class ItemMeta(
    val v: Int = VERSION,
    val id: String,
    val kind: ItemKind,
    val mime: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val w: Int? = null,
    val h: Int? = null,
    val rot: Int? = null,
    val durMs: Long? = null,
    val takenAt: Long? = null,
    val importedAt: Long,
) {
    override fun toString(): String = "ItemMeta(kind=$kind, size=$size)"

    companion object {
        const val VERSION = 1
    }
}

/** Strict JSON codec for [ItemMeta]: unknown keys or another version are rejected, never guessed. */
object ItemMetaCodec {
    /** Upper bound for a `.vmeta` plaintext. Real ones are a few hundred bytes. */
    const val MAX_SIZE = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(meta: ItemMeta): ByteArray = json.encodeToString(ItemMeta.serializer(), meta).toByteArray(Charsets.UTF_8)

    /** Parses and checks the version and that the id matches the file it came from. */
    fun decode(bytes: ByteArray, expectedIdHex: String): ItemMeta {
        if (bytes.size > MAX_SIZE) throw VaultFormatException("item metadata too large")
        val meta = parse(bytes)
        val problem = when {
            meta.v != ItemMeta.VERSION -> "unsupported item metadata version ${meta.v}"
            meta.id != expectedIdHex -> "item metadata belongs to another item"
            meta.size < 0 -> "negative item size"
            else -> return meta
        }
        throw VaultFormatException(problem)
    }

    private fun parse(bytes: ByteArray): ItemMeta {
        val cause = try {
            return json.decodeFromString(ItemMeta.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        }
        throw VaultFormatException("bad item metadata", cause)
    }
}
