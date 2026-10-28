@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.toked.smartshopping.shopping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.toked.smartshopping.R
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CatalogContent(state: CatalogUiState, references: PlanUiState, onAction: (CatalogAction)->Unit, onBack: ()->Unit,
    modifier: Modifier=Modifier, imageContent: @Composable (Offer,Modifier)->Unit = { offer,imageModifier -> CatalogImage(offer,imageModifier) }) {
    BackHandler(onBack=onBack)
    val colors=MaterialTheme.colorScheme
    val plan=state.plan?.plan
    Surface(modifier.fillMaxSize(),color=colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onBack) { Icon(painterResource(R.drawable.ic_auth_arrow_back),"Quay lại kế hoạch") }
                Text("Chọn sản phẩm",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                IconButton({ onAction(CatalogAction.Filters(true)) }) { Icon(painterResource(R.drawable.ic_catalog_filter),"Bộ lọc sản phẩm") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("catalog-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(plan!=null) item {
                    Surface(shape=RoundedCornerShape(20.dp),color=colors.surfaceContainerLowest) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("Ngân sách kế hoạch",color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                                Text(planMoney(plan.budget.amount.toPlainString(),plan.budget.currency),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                            }
                            Text("${plan.selections.size} nơi mua · ${plan.categoryIds.size} nhóm hàng",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("Đã chọn: ${planMoney(plan.budgetSummary.selectedTotal.amount.toPlainString())}",style=MaterialTheme.typography.bodyMedium)
                                Text("Còn lại: ${planMoney(plan.budgetSummary.remainingBudget.amount.toPlainString())}",color=if(plan.budgetSummary.remainingBudget.amount.signum()<0) colors.error else colors.primary,style=MaterialTheme.typography.bodyMedium)
                            }
                            val progress=if(plan.budget.amount.signum()>0) plan.budgetSummary.selectedTotal.amount.divide(plan.budget.amount,4,java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f) else 0f
                            LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth(),color=colors.primary)
                            if(!plan.budgetSummary.complete) Text("Tổng tiền chưa đầy đủ vì có món thiếu giá.",color=colors.error,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(state.search,{onAction(CatalogAction.Query(it))},Modifier.weight(1f),singleLine=true,
                            placeholder={Text("Tìm sản phẩm")},leadingIcon={Icon(painterResource(R.drawable.ic_catalog_search),null)},shape=RoundedCornerShape(16.dp),
                            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={onAction(CatalogAction.Search)}))
                        IconButton({onAction(CatalogAction.Search)}) { Icon(painterResource(R.drawable.ic_auth_arrow_forward),"Tìm sản phẩm") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.filter.retailer==null,{onAction(CatalogAction.Retailer(null))},label={Text("Tất cả nơi mua")})
                        plan?.selections?.mapNotNull {it.retailerId}?.distinct()?.forEach { id ->
                            FilterChip(state.filter.retailer==id,{onAction(CatalogAction.Retailer(id))},label={Text(retailerName(id,references))})
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.filter.category==null,{onAction(CatalogAction.Category(null))},label={Text("Tất cả nhóm hàng")})
                        plan?.categoryIds?.forEach {id -> FilterChip(state.filter.category==id,{onAction(CatalogAction.Category(id))},label={Text(references.categories.firstOrNull {it.id==id}?.name ?: "Nhóm hàng đã chọn")}) }
                    }
                }
                item {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton({onAction(CatalogAction.Filters(true))}) { Text(sortLabel(state.filter.sort)) }
                        TextButton({onAction(CatalogAction.Refresh)},enabled=!state.refreshing && state.retrySeconds==0) {
                            Text(when { state.refreshing -> "Đang cập nhật…";state.retrySeconds>0 -> "Thử lại sau ${state.retrySeconds}s";state.pending!=null -> "Thử cập nhật lại";else -> "Cập nhật sản phẩm" })
                        }
                    }
                }
                if(state.loading || state.refreshing) item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Đang tải sản phẩm…",style=MaterialTheme.typography.bodyMedium) }
                }
                state.error?.let { error -> item { CatalogNotice(error,true);TextButton({onAction(CatalogAction.Reload)}) { Text("Tải lại danh sách") } } }
                state.message?.let { message -> item { CatalogNotice(message) } }
                if(state.status=="PARTIAL") item { CatalogNotice("Một phần dữ liệu chưa đầy đủ. Những sản phẩm đã tải vẫn được hiển thị.") }
                state.warnings.distinctBy {it.code}.take(3).forEach {w -> item(key="warning-${w.code}") { CatalogNotice(w.message) } }
                if(state.items.isEmpty() && !state.loading && !state.refreshing) item {
                    Surface(shape=RoundedCornerShape(20.dp),color=colors.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(if(state.status=="FAILED") "Chưa tải được sản phẩm" else "Chưa có sản phẩm phù hợp",style=MaterialTheme.typography.titleLarge)
                            Text("Bạn có thể thay đổi tìm kiếm, bộ lọc hoặc cập nhật dữ liệu. Kế hoạch vẫn được giữ.",color=colors.onSurfaceVariant)
                        }
                    }
                }
                val grouped=state.items.groupBy {it.retailerId}
                grouped.forEach { (id,offers) ->
                    item(key="retailer-$id") {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(retailerName(id,references),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                            val scopeLabel=if(offers.any {it.warnings.any {w->w.code=="STORE_PRICE_SCOPE_UNVERIFIED"}}) "Phạm vi giá cần xác minh" else "Giá theo chuỗi"
                            Text("${offers.size} sản phẩm đã tải · $scopeLabel",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                        }
                    }
                    items(offers,key={"${it.ref.kind}:${it.ref.id}"}) { offer -> ProductCard(offer,{offer.product?.id?.let {onAction(CatalogAction.Details(it))}},imageContent) }
                }
                state.retailers.filter {it.status in setOf("FAILED","EMPTY")}.forEach {r -> item(key="source-${r.id}") {
                    CatalogNotice("${retailerName(r.id,references)}: ${if(r.status=="EMPTY") "chưa có sản phẩm phù hợp" else "chưa cập nhật được dữ liệu"}.")
                } }
                if(state.nextCursor!=null) item { OutlinedButton({onAction(CatalogAction.More)},Modifier.fillMaxWidth(),enabled=!state.loading && !state.refreshing) { Text("Tải thêm sản phẩm") } }
            }
        }
    }
    if(state.selectedProduct!=null) AlertDialog(onDismissRequest={onAction(CatalogAction.CloseDetails)},
        title={Text(state.detail?.product?.name ?: "Chi tiết sản phẩm")},text={
            Column(Modifier.fillMaxWidth().heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(state.detailLoading) CircularProgressIndicator()
                state.detailError?.let {Text(it);TextButton({onAction(CatalogAction.Details(state.selectedProduct))}) {Text("Thử lại chi tiết")} }
                state.detail?.offers?.forEach {offer ->
                    Text(catalogPrice(offer),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    offer.product?.brandName?.let {Text("Thương hiệu: $it")}
                    Text("Quy cách: ${offer.product?.packageLabel ?: offer.product?.unit.orEmpty()}")
                    Text(if(offer.warnings.any {it.code=="STORE_PRICE_SCOPE_UNVERIFIED"}) "Phạm vi áp dụng giá chưa được xác minh từ dữ liệu cửa hàng." else "Giá theo chuỗi, chưa xác nhận tại chi nhánh.")
                    Text("Tồn kho: Chưa xác nhận")
                    Text("Nguồn: ${offer.source ?: "Chưa xác định"}",style=MaterialTheme.typography.bodySmall)
                    Text("Ghi nhận: ${catalogObserved(offer.observedAt)}",style=MaterialTheme.typography.bodySmall)
                    Text(freshnessLabel(offer.freshness),color=colors.onSurfaceVariant)
                    offer.warnings.forEach {Text(it.message,style=MaterialTheme.typography.bodySmall)}
                }
            }
        },confirmButton={TextButton({onAction(CatalogAction.CloseDetails)}) {Text("Đóng chi tiết")} })
    if(state.filtersOpen) AlertDialog(onDismissRequest={onAction(CatalogAction.Filters(false))},title={Text("Bộ lọc sản phẩm")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Sắp xếp",style=MaterialTheme.typography.titleMedium)
            for(sort in listOf("RELEVANCE","PRICE_ASC","PRICE_DESC")) FilterChip(state.filter.sort==sort,{onAction(CatalogAction.Sort(sort))},label={Text(sortLabel(sort))})
            Text("Thương hiệu",style=MaterialTheme.typography.titleMedium)
            FilterChip(state.filter.brand==null,{onAction(CatalogAction.Brand(null))},label={Text("Tất cả thương hiệu")})
            state.brands.forEach {brand -> FilterChip(state.filter.brand==brand.id,{onAction(CatalogAction.Brand(brand.id))},label={Text(brand.name)}) }
            if(state.brandsLoading) CircularProgressIndicator(Modifier.size(24.dp))
            if(state.brandCursor!=null) TextButton({onAction(CatalogAction.MoreBrands)},enabled=!state.brandsLoading) {Text("Thêm thương hiệu")}
        }
    },confirmButton={TextButton({onAction(CatalogAction.Filters(false))}) {Text("Xong")} })
}

@Composable private fun ProductCard(offer: Offer,onClick: ()->Unit,imageContent: @Composable (Offer,Modifier)->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(onClick=onClick,shape=RoundedCornerShape(16.dp),color=colors.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            imageContent(offer,Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(offer.product?.name ?: "Sản phẩm",style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(listOfNotNull(offer.product?.brandName,offer.product?.packageLabel ?: offer.product?.unit).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(catalogPrice(offer),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=if(offer.price==null) colors.onSurfaceVariant else colors.primary)
                Text(freshnessLabel(offer.freshness),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_auth_arrow_forward),"Xem chi tiết",Modifier.size(20.dp),tint=colors.primary)
        }
    }
}
@Composable private fun CatalogImage(offer: Offer,modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),contentAlignment=Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plan_category),null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.outline)
        val url=offer.product?.imageUrl?.takeIf {it.startsWith("https://")}
        if(url!=null) AsyncImage(model=url,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
    }
}
@Composable private fun CatalogNotice(text: String,error: Boolean=false) {
    val colors=MaterialTheme.colorScheme
    Surface(color=if(error) colors.errorContainer else colors.secondaryContainer,shape=RoundedCornerShape(12.dp)) {
        Text(text,Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=if(error) colors.onErrorContainer else colors.onSecondaryContainer)
    }
}
private fun retailerName(id: String?,references: PlanUiState)=references.retailers.firstOrNull {it.id==id}?.name ?: "Nơi mua đã chọn"
private fun sortLabel(sort: String)=when(sort) {"PRICE_ASC"->"Giá tăng dần";"PRICE_DESC"->"Giá giảm dần";else->"Phù hợp nhất"}
private fun freshnessLabel(value: String)=when(value) {"FRESH"->"Có thời điểm ghi nhận";"STALE"->"Giá đã cũ · kiểm tra lại";"EXPIRED"->"Giá hết hiệu lực";else->"Chưa xác định độ mới"}
private fun catalogPrice(offer: Offer)=offer.price?.let {planMoney(it.amount.toPlainString(),it.currency)} ?: "Chưa xác nhận giá"
private fun catalogObserved(value: String?): String=try {if(value==null) "Chưa xác định" else DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))} catch(_:Exception) {"Chưa xác định"}
