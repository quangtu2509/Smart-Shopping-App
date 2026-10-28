package com.toked.smartshopping.backend

import com.toked.smartshopping.backend.catalog.*
import java.math.BigDecimal
import java.time.Instant

/** Compiled only into tests and the isolated Android HTTP fixture. Never a development seed. */
internal class FixtureCatalogProvider: CatalogProvider {
    val values=(1..26).map { index ->
        val site=if(index<=24) "https://catalog-a.example.test" else "https://catalog-b.example.test"
        ProviderProduct("fixture-$index","sku-$index","Sản phẩm Catalog Test $index",site,"FIXTURE","$site/product/$index",emptyList(),"Thương hiệu Test","sku-$index",
            "hộp","1 hộp","VND",if(index==2) null else BigDecimal(index*1000),null,null,null,emptyList(),0,"unknown",
            BigDecimal(Instant.now().epochSecond),null,null,false,listOf(ProviderCategory("fixture-food","Nhóm Catalog Test $site","$site/food",null)),false,emptyList())
    }
    override suspend fun products(query: CatalogQuery): ProviderPage {
        val matches=values.filter {query.site==null || it.website==query.site}
        return ProviderPage(matches.drop((query.page-1)*query.limit).take(query.limit),matches.size.toLong(),query.page,query.limit,0,emptyList())
    }
}
