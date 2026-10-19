package com.toked.smartshopping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.toked.smartshopping.ui.theme.SmartShoppingTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toked.smartshopping.auth.AuthApi
import com.toked.smartshopping.auth.SecureSessionStore
import com.toked.smartshopping.auth.RegisterViewModel
import com.toked.smartshopping.auth.RegisterScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val authApi = AuthApi(BuildConfig.AUTH_API_BASE_URL, allowLocalHttp = BuildConfig.DEBUG)
        val sessionStore = SecureSessionStore(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RegisterViewModel::class.java))
                return RegisterViewModel(authApi, sessionStore) as T
            }
        }
        setContent {
            SmartShoppingTheme {
                val registerViewModel: RegisterViewModel = viewModel(factory = factory)
                RegisterScreen(registerViewModel)
            }
        }
    }
}
