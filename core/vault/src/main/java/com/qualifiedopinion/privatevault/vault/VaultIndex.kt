package com.qualifiedopinion.privatevault.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The in-memory item list (planning pack §2). Built once per unlock from the `.vmeta` files and
 * cleared on lock. There is no plaintext database. Newest import first.
 */
class VaultIndex {
    private val state = MutableStateFlow<List<ItemMeta>>(emptyList())

    val items: StateFlow<List<ItemMeta>> = state.asStateFlow()

    val size: Int get() = state.value.size

    operator fun get(idHex: String): ItemMeta? = state.value.firstOrNull { it.id == idHex }

    fun replaceAll(items: Collection<ItemMeta>) {
        state.value = sorted(items.distinctBy { it.id })
    }

    fun add(item: ItemMeta) {
        state.update { list -> sorted(list.filterNot { it.id == item.id } + item) }
    }

    fun remove(idHex: String) {
        state.update { list -> list.filterNot { it.id == idHex } }
    }

    fun clear() {
        state.value = emptyList()
    }

    private fun sorted(items: Collection<ItemMeta>): List<ItemMeta> =
        items.sortedWith(compareByDescending<ItemMeta> { it.importedAt }.thenBy { it.id })
}
