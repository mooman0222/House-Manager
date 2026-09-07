import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun props(name: String) = Properties().apply { rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) } }

// 地図キーと署名鍵は Git 管理外。ローカルは local.properties / keystore.properties、CI は環境変数（Secrets）から読む
val mapsApiKey: String = System.getenv("MAPS_API_KEY") ?: props("local.properties").getProperty("MAPS_API_KEY").orEmpty()
val ks = props("keystore.properties")
val ksFile = System.getenv("KEYSTORE_FILE") ?: ks.getProperty("storeFile")

android {
    namespace = "jp.house.report"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.house.report"; minSdk = 26; targetSdk = 35; versionName = "0.1"
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1 // CI のビルド番号をそのまま使い、上書きインストールできるようにする
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (ksFile != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(ksFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ks.getProperty("storePassword")
            keyAlias = System.getenv("KEY_ALIAS") ?: ks.getProperty("keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD") ?: ks.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.google.maps.android:maps-compose:4.4.1")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0") // Gemma を端末内で動かす
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
