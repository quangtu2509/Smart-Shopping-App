package com.toked.smartshopping.auth

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingProfileTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { models.clear(); Dispatchers.resetMain() }
    private class Gateway : FakeAuthGateway() {
        var profile = ShoppingProfile()
        var failure: AuthFailure? = null
        var referenceFailure = false
        var gate: CompletableDeferred<Unit>? = null
        val patches = mutableListOf<ProfilePatch>()
        val access = mutableListOf<String>()
        var refreshes = 0
        override suspend fun me(accessToken: String) = sampleSession().account
        override suspend fun shoppingProfile(accessToken: String) = profile
        override suspend fun saveShoppingProfile(accessToken: String, patch: ProfilePatch): ShoppingProfile {
            access += accessToken; patches += patch; gate?.await()
            failure?.let { throw it }
            val b = JSONObject(patch.body)
            return profile.copy(version = profile.version + 1, maxStores = b.getInt("max_stores"), mode = b.getString("optimization_mode"))
        }
        override suspend fun references(accessToken: String, kind: String, cursor: String?): ReferencePage {
            if (referenceFailure) throw AuthFailure.Network()
            return ReferencePage(listOf(ProfileReference("one", "Một"), ProfileReference("two", "Hai")), null)
        }
        override suspend fun refresh(session: AuthSession): AuthSession { refreshes++; failure = null; return sampleSession("new-access", "new-refresh") }
    }
    private fun vm(api: Gateway = Gateway()): RegisterViewModel = RegisterViewModel(api, MemorySessionStore().apply { saved = sampleSession() }).also {
        models.put("profile", it); it.start(); it.showProfile()
    }

    @Test fun optionalFieldsValidateAndPatchExplicitNulls() {
        assertTrue(ProfileDraft().errors().isEmpty())
        for (value in listOf("0", "-1", "1.5", "99999999999999")) assertTrue("household_size" in ProfileDraft(household = value).errors())
        for (period in listOf("P0D", "P-1M", "hello", "PT2H")) assertTrue("default_period" in ProfileDraft(period = period).errors())
        for (period in listOf("P1W", "P1M", "P1Y2M3D")) assertTrue(ProfileDraft(period = period).errors().isEmpty())
        assertEquals("P2W", wireShoppingPeriod("2 tuần")); assertEquals("P1M3D", wireShoppingPeriod("1 tháng 3 ngày"))
        assertEquals("1 năm 2 tháng 3 ngày", displayShoppingPeriod("P1Y2M3D"))
        assertNull(wireShoppingPeriod("0 tuần")); assertNull(wireShoppingPeriod("1 tuần không hợp lệ"))
        assertEquals("P1W", JSONObject(ProfileDraft(period = "1 tuần").patch(1).body).getString("default_period"))
        val body = JSONObject(ProfileDraft().patch(2).body)
        assertTrue(body.isNull("household_size")); assertTrue(body.isNull("default_period")); assertEquals(2, body.getInt("expected_version"))
    }
    @Test fun loadsServerProfileAndSelectionMovesBetweenPreferenceGroups() = runTest(dispatcher) {
        val vm = vm(Gateway().apply { profile = ShoppingProfile(householdSize = 4, maxStores = 1) })
        assertEquals("4", vm.state.value.profile.draft.household); assertFalse(vm.state.value.profile.dirty)
        assertEquals(1, vm.state.value.account!!.maxStores)
        vm.selectProfileReference("categories", "one", "preferred")
        vm.selectProfileReference("categories", "one", "avoided")
        assertTrue(vm.state.value.profile.draft.preferredCategories.isEmpty())
        assertEquals(listOf("one"), vm.state.value.profile.draft.avoidedCategories)
        vm.selectProfileReference("brands", "missing", "preferred")
        assertTrue(vm.state.value.profile.draft.preferredBrands.isEmpty())
    }
    @Test fun invalidInputIsKeptWithoutCallingServer() = runTest(dispatcher) {
        val api = Gateway(); val vm = vm(api)
        vm.editProfile("household_size", "0"); vm.saveProfile()
        assertTrue("household_size" in vm.state.value.profile.errors); assertEquals("0", vm.state.value.profile.draft.household)
        assertTrue(api.patches.isEmpty())
    }
    @Test fun pendingSaveBlocksDuplicateEditsAndBackThenUpdatesHomeSummary() = runTest(dispatcher) {
        val api = Gateway().apply { gate = CompletableDeferred() }; val vm = vm(api)
        vm.editProfile("max_stores", "1"); vm.editProfile("optimization_mode", "CHEAPEST")
        vm.saveProfile(); vm.saveProfile(); vm.skipOnboarding(); vm.editProfile("max_stores", "3")
        assertEquals(1, api.patches.size); assertEquals(AuthPage.ONBOARDING, vm.state.value.page)
        assertEquals(1, vm.state.value.profile.draft.maxStores)
        api.gate!!.complete(Unit)
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page); assertEquals(1, vm.state.value.account!!.maxStores)
        assertEquals("CHEAPEST", vm.state.value.account!!.mode)
    }
    @Test fun unknownSaveOutcomeRetriesExactKeyAndPayloadWithoutEditing() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Network() }; val vm = vm(api)
        vm.editProfile("household_size", "4"); vm.saveProfile()
        assertTrue(vm.state.value.profile.uncertainSave)
        vm.editProfile("household_size", "8"); assertEquals("4", vm.state.value.profile.draft.household)
        api.failure = null; vm.saveProfile()
        assertEquals(api.patches[0].key, api.patches[1].key); assertEquals(api.patches[0].body, api.patches[1].body)
        assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
    }
    @Test fun conflictKeepsDraftUntilExplicitReloadConfirmation() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Http(409,"VERSION_CONFLICT", emptyMap(),null) }; val vm = vm(api)
        vm.editProfile("household_size", "4"); vm.saveProfile(); vm.saveProfile()
        assertEquals(1, api.patches.size); assertEquals("4", vm.state.value.profile.draft.household)
        vm.profileDialog("reload"); assertTrue(vm.state.value.profile.confirmReload)
        vm.profileDialog("cancel"); assertEquals("4", vm.state.value.profile.draft.household)
        api.profile = ShoppingProfile(version = 3, householdSize = 2)
        vm.profileDialog("confirmReload")
        assertEquals("2", vm.state.value.profile.draft.household); assertFalse(vm.state.value.profile.conflict)
    }
    @Test fun fieldFailureAllowsCorrectionWithNewIntentKey() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Http(422,"VALIDATION_ERROR", mapOf("household_size" to "INVALID"),null) }; val vm = vm(api)
        vm.editProfile("household_size", "4"); vm.saveProfile()
        assertTrue(vm.state.value.profile.editable); assertTrue("household_size" in vm.state.value.profile.errors)
        vm.editProfile("household_size", "5"); api.failure = null; vm.saveProfile()
        assertNotEquals(api.patches[0].key, api.patches[1].key)
    }
    @Test fun draftSurvivesResumeReferenceFailureAndCancelledLeave() = runTest(dispatcher) {
        val api = Gateway().apply { referenceFailure = true }; val vm = vm(api)
        assertTrue(vm.state.value.profile.referenceError)
        vm.editProfile("household_size", "3"); vm.onResume(); vm.skipOnboarding()
        assertEquals(AuthPage.ONBOARDING, vm.state.value.page); assertTrue(vm.state.value.profile.confirmLeave)
        vm.profileDialog("cancel"); api.referenceFailure = false; vm.retryProfileReferences()
        assertEquals("3", vm.state.value.profile.draft.household); assertFalse(vm.state.value.profile.referenceError)
        vm.skipOnboarding(); vm.profileDialog("discard"); assertEquals(AuthPage.HOME_ENTRY, vm.state.value.page)
    }
    @Test fun profileMutationRefreshKeepsSameIdempotencyKey() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Http(401,"ACCESS_TOKEN_EXPIRED", emptyMap(), null) }; val vm = vm(api)
        vm.saveProfile()
        assertEquals(1, api.refreshes); assertEquals(listOf("old-access", "new-access"), api.access)
        assertEquals(api.patches[0].key, api.patches[1].key)
    }
    @Test fun revokedSessionClearsProfileAndReturnsToLogin() = runTest(dispatcher) {
        val api = Gateway().apply { failure = AuthFailure.Http(401,"SESSION_REVOKED", emptyMap(),null) }; val vm = vm(api)
        vm.editProfile("household_size", "5"); vm.saveProfile()
        assertEquals(AuthPage.LOGIN_ENTRY, vm.state.value.page); assertNull(vm.state.value.account); assertNull(vm.state.value.profile.saved)
    }
    @Test fun apiSendsTypedPatchBearerAndStableKeyAndDecodesReferences() = runTest {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val api = AuthApi(server.url("/api/v1/").newBuilder().host("127.0.0.1").build().toString(), true)
            val patch = ProfileDraft(household = "3", period = "P1W", preferredCategories = listOf("one")).patch(1)
            val data = JSONObject(patch.body).apply { remove("expected_version"); put("version",2) }
            server.enqueue(MockResponse().setBody(JSONObject().put("data", data).toString()))
            assertEquals(3, api.saveShoppingProfile("access",patch).householdSize)
            val request = server.takeRequest()
            assertEquals("PATCH",request.method); assertEquals("Bearer access",request.getHeader("Authorization"))
            assertEquals(patch.key,request.getHeader("Idempotency-Key")); assertEquals(patch.body,request.body.readUtf8())
            server.enqueue(MockResponse().setBody("""{"data":{"items":[{"id":"one","name":"Một","status":"ACTIVE"}],"next_cursor":"next"}}"""))
            assertEquals("next",api.references("access","brands","a+b").nextCursor)
            assertTrue(server.takeRequest().path!!.contains("cursor=a%2Bb"))
        }
    }
}
