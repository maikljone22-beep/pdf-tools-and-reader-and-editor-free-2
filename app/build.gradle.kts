import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smartdocs.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartdocs.editor"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        vectorDrawables.useSupportLibrary = true
        // AI proxy (see ai-proxy/README.md): users need no API key when these are set in ai.properties
        val aiProps = Properties().apply { rootProject.file("ai.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "AI_PROXY_URL", "\"${aiProps.getProperty("proxyUrl", "")}\"")
        buildConfigField("String", "AI_APP_TOKEN", "\"${aiProps.getProperty("appToken", "")}\"")
        val cloudProps = Properties().apply { rootProject.file("cloud.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        for ((k, prop) in listOf("CLOUD_ONEDRIVE_CLIENT_ID" to "onedriveClientId", "CLOUD_DROPBOX_APP_KEY" to "dropboxAppKey",
                "CLOUD_ONEDRIVE_REDIRECT" to "onedriveRedirect", "CLOUD_DROPBOX_REDIRECT" to "dropboxRedirect",
                "COLLAB_URL" to "collabUrl", "COLLAB_TOKEN" to "collabToken", "SUPPORT_EMAIL" to "supportEmail",
                // share6: Box, share, esign, connected workers
                "CLOUD_BOX_CLIENT_ID" to "boxClientId", "CLOUD_BOX_REDIRECT" to "boxRedirect", "CLOUD_BOX_CLIENT_SECRET" to "boxClientSecret", "CLOUD_BOX_TOKEN_URL" to "boxTokenUrl",
                "SHARE_URL" to "shareUrl", "SHARE_TOKEN" to "shareToken",
                "ESIGN_URL" to "esignUrl", "ESIGN_TOKEN" to "esignToken", "CONNECTED_URL" to "connectedUrl", "CONNECTED_TOKEN" to "connectedToken"))
            buildConfigField("String", k, "\"${cloudProps.getProperty(prop, "")}\"")
        val proProps = Properties().apply { rootProject.file("pro.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "PRO_CODE_SECRET", "\"${proProps.getProperty("codeSecret", "")}\"")
        // share6 (monet): AdMob — OFF unless ads.properties sets enabled=true; the manifest always needs an app id (Google's sample id by default)
        val adsProps = Properties().apply { rootProject.file("ads.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "ADS_ENABLED", "\"${adsProps.getProperty("enabled", "false")}\"")
        buildConfigField("String", "ADS_BANNER_ID", "\"${adsProps.getProperty("bannerId", "")}\"")
        buildConfigField("String", "ADS_INTERSTITIAL_ID", "\"${adsProps.getProperty("interstitialId", "")}\"")
        manifestPlaceholders["admobAppId"] = adsProps.getProperty("appId", "ca-app-pub-3940256099942544~3347511713")
        // share6 (monet): licence server
        val licProps = Properties().apply { rootProject.file("license.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "LICENSE_URL", "\"${licProps.getProperty("licenseUrl", "")}\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"${licProps.getProperty("licensePublicKey", "")}\"")
    }
    // release signing: keystore.properties + release.jks in the project root (kept out of git; back them up!)
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // test builds install next to the Play/release app instead of conflicting with it
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }
    // one smaller APK per processor type (+ a universal one); Google Play uses the .aab instead
    splits {
        abi {
            // the Play bundle splits by itself; per-ABI APKs only for direct installs
            isEnable = !gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")

    val composeBom = platform("androidx.compose:compose-bom:2025.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.exifinterface:exifinterface:1.4.0")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // PDF engine: PDFium (rendering, text) + PdfBox (editing, forms, security)
    implementation("io.legere:pdfiumandroid:2.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.mozilla:rhino:1.7.15.1")
    implementation("androidx.window:window:1.2.0")   // foldable / dual screen (gaps6)   // form JavaScript (forms5b)

    // Scanner + OCR
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android-openmp:4.8.0")

    // Handwriting → text (supports Arabic), Play Billing, in-app review, Google sign-in/authorization (Drive)
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.google.android.play:review-ktx:2.0.2")
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Ads (share6/monet; used only by core/ads; OFF unless ads.properties enabled=true) — needs the APPLICATION_ID meta-data in the manifest
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    // Images + network
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Digital signatures
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.72")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.72")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
