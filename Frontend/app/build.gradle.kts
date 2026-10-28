plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.toked.smartshopping"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.toked.smartshopping"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"803153983048-beki7emtkn0ph45sjm5bs4emud5mkn1o.apps.googleusercontent.com\"")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["additionalTestOutputDir"] = "/sdcard/Android/media/com.toked.smartshopping/auth01-evidence"
    }

    buildTypes {
        debug {
            val apiUrl = providers.gradleProperty("smartShoppingDebugApiBaseUrl")
                .orElse("http://10.0.2.2:8080/api/v1/").get()
            val integrationUrl = providers.gradleProperty("android.testInstrumentationRunnerArguments.authIntegrationBaseUrl").orNull
            require(integrationUrl.isNullOrBlank() || apiUrl == integrationUrl) {
                "Integration tests require smartShoppingDebugApiBaseUrl to match authIntegrationBaseUrl for the isolated fixture"
            }
            buildConfigField("String", "AUTH_API_BASE_URL", "\"${apiUrl.replace("\"", "\\\"")}\"")
        }
        release {
            val apiUrl = providers.gradleProperty("smartShoppingReleaseApiBaseUrl").orNull.orEmpty()
            require(apiUrl.isEmpty() || apiUrl.startsWith("https://")) { "Release API URL must use HTTPS" }
            buildConfigField("String", "AUTH_API_BASE_URL", "\"${apiUrl.replace("\"", "\\\"")}\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.id)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
