package com.toked.smartshopping.backend

import org.junit.Test
import java.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.Signature
import java.math.BigInteger
import java.util.Date
import java.io.ByteArrayInputStream
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.util.Clock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class GoogleTokenVerifierTest {
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun token(audience: String, expiry: Long) = encoded("""{"alg":"RS256","typ":"JWT"}""") + "." +
        encoded("""{"iss":"https://accounts.google.com","aud":"$audience","sub":"fake-subject","exp":$expiry,"iat":1,"email":"fake@gmail.com","email_verified":true,"nonce":"nonce"}""") + "." + encoded("invalid-signature")
    @Test fun wrongAudienceExpiredAndMalformedTokensCannotAuthenticate() {
        val verifier = GoogleTokenVerifier("expected-client")
        for (value in listOf("malformed",token("other-client",4102444800),token("expected-client",1))) {
            val failure = assertFailsWith<ApiFailure> { verifier.verify(value) }
            assertEquals("GOOGLE_TOKEN_INVALID",failure.code)
        }
    }
    @Test fun trustedSignatureAuthenticatesAndAttackerSignatureIsRejectedOffline() {
        val fixture = TrustedTokens()
        val result = fixture.verifier.verify(fixture.signed())
        assertEquals("trusted-subject",result.subject); assertEquals("bound-nonce",result.nonce); assertTrue(result.verified)
        val attacker = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertEquals("GOOGLE_TOKEN_INVALID",assertFailsWith<ApiFailure> { fixture.verifier.verify(fixture.signed(pair = attacker)) }.code)
    }
    @Test fun futureIssuedAtAllowsAtMostThirtySecondsOfClockDifference() {
        val fixture = TrustedTokens()
        for (offset in listOf(0L, 1L, 29L, 30L)) {
            assertEquals("trusted-subject", fixture.verifier.verify(fixture.signed(issued = fixture.now + offset)).subject)
        }
        assertEquals("GOOGLE_TOKEN_INVALID", assertFailsWith<ApiFailure> {
            fixture.verifier.verify(fixture.signed(issued = fixture.now + 31))
        }.code)
    }
    @Test fun expirationToleranceStopsAtThirtySeconds() {
        val fixture = TrustedTokens()
        for (offset in listOf(0L, 1L, 29L, 30L)) {
            assertEquals("trusted-subject", fixture.verifier.verify(fixture.signed(issued = fixture.now - 600, expiry = fixture.now - offset)).subject)
        }
        assertEquals("GOOGLE_TOKEN_INVALID", assertFailsWith<ApiFailure> {
            fixture.verifier.verify(fixture.signed(issued = fixture.now - 600, expiry = fixture.now - 31))
        }.code)
    }
    @Test fun signedWrongAudienceAndIssuerAreRejectedInsideClockTolerance() {
        val fixture = TrustedTokens()
        for (value in listOf(fixture.signed(issued = fixture.now + 1, audience = "attacker-client"),
            fixture.signed(issued = fixture.now + 1, issuer = "https://attacker.invalid"))) {
            assertEquals("GOOGLE_TOKEN_INVALID", assertFailsWith<ApiFailure> { fixture.verifier.verify(value) }.code)
        }
    }
    private class TrustedTokens {
        val now = 1_792_368_000L
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = JcaX509v3CertificateBuilder(X500Name("CN=Test only"),BigInteger.ONE,
            Date((now-60)*1000),Date((now+3600)*1000),X500Name("CN=Test only"),keys.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64,"\n".toByteArray()).encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----"
        val body = buildJsonObject { put("test-key",pem) }.toString().toByteArray()
        val transport = object : HttpTransport() {
            override fun buildRequest(method: String,url: String): LowLevelHttpRequest = object : LowLevelHttpRequest() {
                override fun addHeader(name: String,value: String) = Unit
                override fun execute(): LowLevelHttpResponse = object : LowLevelHttpResponse() {
                    override fun getContent() = ByteArrayInputStream(body)
                    override fun getContentEncoding(): String? = null
                    override fun getContentLength() = body.size.toLong()
                    override fun getContentType() = "application/json"
                    override fun getStatusLine() = "HTTP/1.1 200 OK"
                    override fun getStatusCode() = 200
                    override fun getReasonPhrase() = "OK"
                    override fun getHeaderCount() = 1
                    override fun getHeaderName(index: Int) = "Cache-Control"
                    override fun getHeaderValue(index: Int) = "public, max-age=3600"
                }
            }
        }
        val verifier = GoogleTokenVerifier("expected-client",transport, Clock { now * 1000 })
        fun signed(pair: KeyPair = keys, issued: Long = now, expiry: Long = now + 600,
            audience: String = "expected-client", issuer: String = "https://accounts.google.com"): String {
            fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
            val value = encoded("""{"alg":"RS256","typ":"JWT","kid":"test-key"}""") + "." +
                encoded("""{"iss":"$issuer","aud":"$audience","sub":"trusted-subject","exp":$expiry,"iat":$issued,"email":"trusted@gmail.com","email_verified":true,"nonce":"bound-nonce"}""")
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(value.toByteArray()) }.sign()
            return value + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        }
    }
}
