import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Server delle segnalazioni: da local.properties, gradle.properties o variabili d'ambiente. Vuoto = solo sul telefono. */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun config(name: String, env: String): String =
    localProps.getProperty(name) ?: (findProperty(name) as String?) ?: System.getenv(env) ?: ""

android {
    namespace = "it.roma.metroa"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.roma.metroa"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${config("metroa.supabaseUrl", "METROA_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${config("metroa.supabaseAnonKey", "METROA_SUPABASE_ANON_KEY")}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    // Due app dallo stesso codice: "user" è MetroA per tutti, "dev" è MetroA Dev per gli sviluppatori
    // (installabile accanto, con in più le schermate in src/dev: numeri dei treni, diagnostica GPS, dati)
    flavorDimensions += "app"
    productFlavors {
        create("user") { dimension = "app" }
        create("dev") {
            dimension = "app"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // org.json vero al posto degli stub di android.jar, e un server HTTP finto per provare ReportApi
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
