package com.toked.smartshopping.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.toked.smartshopping.BuildConfig

internal suspend fun googleIdToken(context: Context, nonce: String): String {
    val request = GetCredentialRequest.Builder().addCredentialOption(
        GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build()
    ).build()
    val response = try { CredentialManager.create(context).getCredential(context,request) }
        catch (_: GetCredentialCancellationException) { throw AuthFailure.GoogleCancelled() }
    val credential = response.credential as? CustomCredential ?: throw AuthFailure.InvalidResponse()
    if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw AuthFailure.InvalidResponse()
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}

internal suspend fun clearGoogleState(context: Context) {
    CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
}
