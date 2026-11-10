package com.toked.smartshopping.backend.catalog

internal data class CatalogProgress(val dataset: String, val cursor: String)
internal data class CatalogDeltaBatch(val products: List<ProviderProduct>, val deleted: Set<String>,
    val dataset: String, val cursor: String, val previous: CatalogProgress?, val reset: Boolean, val rejected: Int, val observations: List<ProviderObservation>)

/** All provider I/O finishes before the importer opens its cache writer transaction. */
internal object CatalogDeltaSync {
    suspend fun fetch(source: IncrementalCatalogProvider, previous: CatalogProgress?, maxPages: Int): CatalogDeltaBatch {
        return try { fetchOnce(source,previous,maxPages,previous) }
        catch (failure: ProviderFailure) {
            if (failure.code != ProviderFailureCode.DATASET_RESET) throw failure
            fetchOnce(source,null,maxPages,previous)
        }
    }
    private suspend fun fetchOnce(source: IncrementalCatalogProvider, start: CatalogProgress?, maxPages: Int,
        previous: CatalogProgress?): CatalogDeltaBatch {
        val products=linkedMapOf<String,ProviderProduct>();val deleted=linkedSetOf<String>();val observations=linkedMapOf<String,ProviderObservation>()
        var dataset=start?.dataset;var cursor=start?.cursor;var rejected=0
        if (start == null) {
            var after=0L;var baseline:String?=null;var complete=false
            for (pageNumber in 1..maxPages) {
                val page=source.productSnapshot(after,baseline)
                if (dataset != null && dataset != page.datasetId || baseline != null && baseline != page.baseline)
                    throw ProviderFailure(ProviderFailureCode.DATASET_RESET)
                dataset=page.datasetId;baseline=page.baseline;cursor=page.nextCursor;rejected+=page.rejected
                page.items.forEach { products[it.id]=it }
                if (!page.hasMore) { complete=true;break }
                if (page.after<=after) throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE)
                after=page.after
            }
            if (!complete) throw ProviderFailure(ProviderFailureCode.UNAVAILABLE,retryable=true)
        }
        var complete=false
        for (pageNumber in 1..maxPages) {
            val page=source.productChanges(cursor ?: throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE))
            if (page.datasetId!=dataset) throw ProviderFailure(ProviderFailureCode.DATASET_RESET)
            rejected+=page.rejected
            page.items.forEach { event ->
                if (event.operation=="observed") event.observation?.let { observations[it.sourceId]=it }
                else if (event.operation=="delete") { products.remove(event.id);deleted+=event.id }
                else event.product?.let { products[event.id]=it;deleted.remove(event.id) }
            }
            if (page.hasMore && page.nextCursor==cursor) throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE)
            cursor=page.nextCursor
            if (!page.hasMore) { complete=true;break }
        }
        if (!complete) throw ProviderFailure(ProviderFailureCode.UNAVAILABLE,retryable=true)
        return CatalogDeltaBatch(products.values.toList(),deleted,dataset ?: throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE),
            cursor ?: throw ProviderFailure(ProviderFailureCode.INVALID_RESPONSE),previous,start==null,rejected,observations.values.toList())
    }
}
