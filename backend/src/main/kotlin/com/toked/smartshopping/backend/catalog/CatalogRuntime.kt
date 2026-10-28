package com.toked.smartshopping.backend.catalog

import io.ktor.util.AttributeKey

val catalogProviderKey = AttributeKey<CatalogProvider>("catalog-provider")

/** CLI boundary also used by the local launcher. No auth/database initialization or data writes. */
suspend fun checkCatalogConnection(provider: CatalogProvider): String {
    val page = provider.products(CatalogQuery(limit = 1))
    return "CatalogFlow Kotlin adapter: connected; total=${page.total}; decoded=${page.items.size}; rejected=${page.rejectedItems}; partial=${page.partial}."
}
