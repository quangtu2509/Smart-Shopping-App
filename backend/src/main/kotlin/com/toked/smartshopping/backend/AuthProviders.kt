package com.toked.smartshopping.backend

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.http.HttpTransport
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

fun validNewPassword(value: String): Boolean {
    val points = value.codePoints().toArray()
    return points.size in 10..128 && points.any(Character::isUpperCase) && points.any(Character::isLowerCase) &&
        points.any { Character.getType(it) in Character.DASH_PUNCTUATION.toInt()..Character.FINAL_QUOTE_PUNCTUATION.toInt() }
}

fun interface AuthMailer { fun send(email: String, code: String, purpose: String) }
data class GoogleIdentity(val subject: String, val email: String, val verified: Boolean, val nonce: String)
fun interface GoogleVerifier { fun verify(token: String): GoogleIdentity }

class GoogleTokenVerifier(clientId: String, transport: HttpTransport = GoogleNetHttpTransport.newTrustedTransport()) : GoogleVerifier {
    private val verifier = GoogleIdTokenVerifier.Builder(transport, GsonFactory.getDefaultInstance())
        .setAudience(listOf(clientId)).setAcceptableTimeSkewSeconds(0).build()
    override fun verify(token: String): GoogleIdentity {
        if (token.length > 8192) throw ApiFailure(401, "GOOGLE_TOKEN_INVALID")
        val payload = try { verifier.verify(token)?.payload } catch (_: Exception) { null }
            ?: throw ApiFailure(401, "GOOGLE_TOKEN_INVALID")
        return GoogleIdentity(payload.subject ?: throw ApiFailure(401, "GOOGLE_TOKEN_INVALID"),
            payload.email.orEmpty(), payload.emailVerified == true, payload["nonce"] as? String ?: "")
    }
}

class SmtpAuthMailer(private val username: String, private val password: String, host: String = "smtp.gmail.com", port: Int = 587) : AuthMailer {
    private val session = Session.getInstance(Properties().apply {
        setProperty("mail.smtp.host", host)
        setProperty("mail.smtp.port", port.toString())
        setProperty("mail.smtp.auth", "true")
        setProperty("mail.smtp.starttls.enable", "true")
        setProperty("mail.smtp.starttls.required", "true")
        setProperty("mail.smtp.ssl.checkserveridentity", "true")
        setProperty("mail.smtp.connectiontimeout", "10000")
        setProperty("mail.smtp.timeout", "10000")
        setProperty("mail.smtp.writetimeout", "10000")
    })
    override fun send(email: String, code: String, purpose: String) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(username, "Smart Shopping", "UTF-8"))
            setRecipient(Message.RecipientType.TO, InternetAddress(email, true))
            setSubject(if (purpose == "RESET") "Smart Shopping — Khôi phục mật khẩu" else "Smart Shopping — Xác minh email", "UTF-8")
            setText("Mã xác minh của bạn: $code\nMã hết hạn sau 10 phút. Không chia sẻ mã này.\nNếu bạn không yêu cầu, hãy bỏ qua email này.", "UTF-8")
        }
        session.getTransport("smtp").use { transport ->
            transport.connect(username, password)
            transport.sendMessage(message, message.allRecipients)
        }
    }
}
