package com.toked.smartshopping.shopping

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.toked.smartshopping.BuildConfig
import com.toked.smartshopping.auth.*
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.util.UUID

class CatalogUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun view():ShoppingPlanView {
        val amount=Money("VND",BigDecimal("500000"));val zero=Money("VND",BigDecimal.ZERO)
        val summary=BudgetSummary(amount,zero,amount,true,emptyList(),zero,emptyList())
        return ShoppingPlanView(ShoppingPlan("plan",3,0,PlanState.BROWSING_PRODUCTS,amount,listOf(Selection("r1",null)),listOf("c1"),3,summary,"READY","2026-10-28T00:00:00Z","2026-10-28T00:00:00Z"),emptyList(),null,null,emptyList())
    }
    private fun offer(index:Int,unknown:Boolean=false):Offer {
        val p=Product("p$index","Thực phẩm kiểm thử $index","c1","hộp",Quantity.parse("1"),null,null,null,null)
        return Offer(OfferRef("CATALOG","o$index"),"v1",p,null,"r1",null,"VND",if(unknown)null else Money("VND",BigDecimal("30000")),"https://fixture.example.test", "2026-10-28T00:00:00Z","RETAILER","STALE","UNKNOWN",null,null,null,null,emptyList())
    }
    private val refs=PlanUiState(retailers=listOf(PlanReference("r1","Nơi mua kiểm thử","ACTIVE")),categories=listOf(PlanReference("c1","Thực phẩm","ACTIVE")))
    private fun initial()=CatalogUiState(plan=view(),status="READY",items=listOf(offer(1),offer(2,true)),nextCursor="next")
    @Composable private fun Frame(state:CatalogUiState,onAction:(CatalogAction)->Unit={},font:Float=1f,dark:Boolean=false,onBack:()->Unit={}) {
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
            SmartShoppingTheme(darkTheme=dark) {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.width(360.dp).fillMaxHeight().testTag("catalog-viewport")) {
                        CatalogContent(state,refs,onAction,onBack,Modifier.fillMaxSize(),imageContent={_,m->Box(m.background(androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer))})
                    }
                }
            }
        }
    }
    private fun picture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("catalogEvidence")!="true") return
        compose.waitForIdle()
        val dir=compose.activity.getExternalFilesDir("catalog01-evidence")!!;dir.mkdirs()
        // Host ADB captures the visible window. Avoid UiAutomation/PixelCopy connection races on API 35.
        val token=UUID.randomUUID().toString().replace("-","")
        val ack=File(dir,"capture-ack-$token.txt")
        val request=File(dir,"capture-request.txt");request.writeText("$name|$token")
        val deadline=android.os.SystemClock.elapsedRealtime()+20000
        while(android.os.SystemClock.elapsedRealtime()<deadline) {
            if(ack.exists() && ack.readText().trim()==token) break
            android.os.SystemClock.sleep(100)
        }
        check(ack.exists() && ack.readText().trim()==token){"Start scripts/capture-catalog-evidence.ps1 when catalogEvidence=true"}
        request.delete()
    }
    private fun list()=compose.onNodeWithTag("catalog-list")
    @Test fun budgetSearchAndFiltersUseRealStateAndCallbacks() {
        val actions=mutableListOf<CatalogAction>()
        compose.setContent {Frame(initial(),{actions+=it})}
        compose.onNodeWithText("Ngân sách kế hoạch").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("sữa")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertTrue(CatalogAction.Query("sữa") in actions);assertEquals(CatalogAction.Search,actions.last())
        compose.onNodeWithText("Thực phẩm",useUnmergedTree=true).performClick()
        assertEquals(CatalogAction.Category("c1"),actions.last())
        picture("catalog-light")
    }
    @Test fun paginationAndDetailsDispatchCorrectIdentities() {
        val actions=mutableListOf<CatalogAction>()
        compose.setContent {Frame(initial(),{actions+=it})}
        list().performScrollToNode(hasText("Thực phẩm kiểm thử 1"))
        compose.onNodeWithText("Thực phẩm kiểm thử 1").performClick()
        assertEquals(CatalogAction.Details("p1"),actions.last())
        list().performScrollToNode(hasText("Tải thêm sản phẩm"))
        compose.onNodeWithText("Tải thêm sản phẩm").performClick()
        assertEquals(CatalogAction.More,actions.last())
    }
    @Test fun unknownPriceAndPartialRemainVisibleWithoutZeroPrice() {
        compose.setContent {Frame(initial().copy(status="PARTIAL"))}
        list().performScrollToNode(hasText("Chưa xác nhận giá"))
        compose.onNodeWithText("Chưa xác nhận giá").assertIsDisplayed()
        compose.onAllNodesWithText("Giá đã cũ · kiểm tra lại").onFirst().assertExists()
        picture("catalog-partial-missing-price")
    }
    @Test fun emptyAndFailureOfferARecoverableRead() {
        val actions=mutableListOf<CatalogAction>()
        compose.setContent {Frame(initial().copy(items=emptyList(),status="FAILED",error="Chưa tải được dữ liệu.",nextCursor=null),{actions+=it})}
        list().performScrollToNode(hasText("Tải lại danh sách"))
        compose.onNodeWithText("Tải lại danh sách").performClick();assertEquals(CatalogAction.Reload,actions.last())
        list().performScrollToNode(hasText("Chưa tải được sản phẩm"))
        picture("catalog-failed")
    }
    @Test fun detailsExplainSourceScopeAndUnknownStock() {
        val selected=offer(1)
        val actions=mutableListOf<CatalogAction>()
        compose.setContent {Frame(initial().copy(selectedProduct="p1",detail=CatalogDetail(selected.product!!,listOf(selected),emptyList())),{actions+=it})}
        compose.onNodeWithText("Giá theo chuỗi, chưa xác nhận tại chi nhánh.").assertIsDisplayed()
        compose.onNodeWithText("Tồn kho: Chưa xác nhận").assertIsDisplayed()
        picture("catalog-detail")
        compose.onNodeWithText("Đóng chi tiết").performClick();assertEquals(CatalogAction.CloseDetails,actions.last())
    }
    @Test fun filtersAndRateLimitAreAccessible() {
        val actions=mutableListOf<CatalogAction>()
        compose.setContent {Frame(initial().copy(filtersOpen=true,retrySeconds=20),{actions+=it})}
        compose.onNodeWithText("Giá tăng dần").performClick();assertEquals(CatalogAction.Sort("PRICE_ASC"),actions.last())
        compose.onNodeWithText("Xong").performClick();assertEquals(CatalogAction.Filters(false),actions.last())
    }
    @Test fun unverifiedStorePriceIsNotPresentedAsAChainPrice() {
        val scoped=offer(1,true).copy(warnings=listOf(ShoppingWarning("STORE_PRICE_SCOPE_UNVERIFIED","Cần xác minh chi nhánh.",emptyList())))
        compose.setContent {Frame(initial().copy(selectedProduct="p1",detail=CatalogDetail(scoped.product!!,listOf(scoped),emptyList())))}
        compose.onNodeWithText("Phạm vi áp dụng giá chưa được xác minh từ dữ liệu cửa hàng.").assertIsDisplayed()
        compose.onNodeWithText("Giá theo chuỗi, chưa xác nhận tại chi nhánh.").assertDoesNotExist()
    }
    @Test fun largeFontDarkCanScrollCardsAndNavigateBack() {
        var back=false
        compose.setContent {Frame(initial(),font=2f,dark=true,onBack={back=true})}
        compose.onNodeWithContentDescription("Quay lại kế hoạch").assertIsDisplayed()
        list().performScrollToNode(hasText("Thực phẩm kiểm thử 1"))
        compose.onNodeWithText("Thực phẩm kiểm thử 1").assertIsDisplayed()
        picture("catalog-dark-font200")
        compose.onNodeWithContentDescription("Quay lại kế hoạch").performClick();assertTrue(back)
    }
    private class Store:SessionStore {
        var saved:AuthSession?=null
        override suspend fun save(session:AuthSession){saved=session}
        override suspend fun load()=saved
        override suspend fun clear(){saved=null}
    }
    @Test fun livePlanCatalogSearchPaginationDetailAndRefreshUseIsolatedApi() {
        val base=InstrumentationRegistry.getArguments().getString("authIntegrationBaseUrl")
        require(base=="http://127.0.0.1:18080/api/v1/" && BuildConfig.AUTH_API_BASE_URL==base)
        val api=AuthApi(base,allowLocalHttp=true);val store=Store()
        runBlocking {store.save(fixtureRegister(api,base,"catalog-device-${UUID.randomUUID()}@gmail.com","Device catalog password!"))}
        val access=store.saved!!.accessToken
        val created=runBlocking {
            val retailer=api.planReferences(access,"retailers",null).items.first {it.name.contains("catalog-a")}
            val category=api.planReferences(access,"categories",null).items.first {it.name.contains("catalog-a")}
            api.savePlan(access,PlanMutation(UUID.randomUUID().toString(),PlanDraft("500000",selections=listOf(Selection(retailer.id,null)),categoryIds=listOf(category.id)).body()))
        }
        val factory=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=RegisterViewModel(api,store) as T
        }
        lateinit var vm:RegisterViewModel
        compose.activityRule.scenario.onActivity {vm=ViewModelProvider(it,factory)[RegisterViewModel::class.java]}
        compose.setContent {SmartShoppingTheme {RegisterScreen(vm)}}
        compose.waitUntil(15000){vm.state.value.page==AuthPage.HOME_ENTRY}
        compose.runOnIdle {vm.plans.onAction(PlanAction.Open(created.plan.id))}
        compose.waitUntil(15000){vm.plans.state.value.saved?.plan?.id==created.plan.id && !vm.plans.state.value.opening}
        compose.onNodeWithText("Xem sản phẩm").performClick()
        compose.waitUntil(20000){!vm.plans.catalog.state.value.loading && !vm.plans.catalog.state.value.refreshing && vm.plans.catalog.state.value.items.size==20 && vm.plans.state.value.saved?.plan?.status==PlanState.BROWSING_PRODUCTS}
        assertEquals(PlanState.BROWSING_PRODUCTS,vm.plans.state.value.saved!!.plan.status)
        list().performScrollToNode(hasText("Tải thêm sản phẩm"))
        compose.onNodeWithText("Tải thêm sản phẩm").performClick()
        compose.waitUntil(15000){vm.plans.catalog.state.value.items.size==24 && !vm.plans.catalog.state.value.loading}
        assertTrue(vm.plans.catalog.state.value.items.any {it.price==null})
        val first=vm.plans.catalog.state.value.items.first()
        list().performScrollToNode(hasText(first.product!!.name))
        compose.onNodeWithText(first.product!!.name).performClick()
        compose.waitUntil(15000){vm.plans.catalog.state.value.detail!=null}
        picture("catalog-live-detail")
        compose.onNodeWithText("Đóng chi tiết").performClick()
        list().performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).performTextReplacement("Catalog Test 26")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15000){!vm.plans.catalog.state.value.loading && vm.plans.catalog.state.value.items.isEmpty()}
        list().performScrollToNode(hasText("Chưa có sản phẩm phù hợp"))
        picture("catalog-live-empty-outside-retailer")
        assertTrue(runBlocking {api.plan(access,created.plan.id)}.items.isEmpty())
        compose.runOnIdle {vm.plans.clear()};runBlocking {api.logout(store.saved!!.refreshToken)}
    }
}
