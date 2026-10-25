plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.serhii.ifiles"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.serhii.ifiles"
        minSdk = 30
        targetSdk = 36
        versionCode = 11
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    // PdfBox CJK CMaps (~3 MB) are not bundled: Identity-H/V (most embedded fonts) stay; CJK PDFs fall back to name search.
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!*RKSJ-H:!*RKSJ-V:!Adobe-*:!UniCNS*:!UniGB*:!UniJIS*:!UniKS*:!*EUC-H:!*EUC-V:!B5pc-*:!ETen*:!GBK2K-*:!GBpc-*:!HKscs-*:!KSCms-*:!KSCpc-*" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Match the explicit Actions cache path, independent of Android SDK/XDG defaults.
    signingConfigs.getByName("debug").storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
    // Present only in the private source backup, never committed to GitHub.
    if (rootProject.file("signing/debug.keystore").exists()) {
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
dependencies {
    implementation("org.apache.poi:poi-scratchpad:5.4.1")
    implementation("net.sourceforge.jexcelapi:jxl:2.6.12")
    // PDF text for search only: BouncyCastle (encrypted/signed PDFs, ~5 MB) is left out; such files are indexed by name.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
// Respect the developer's JVM proxy when Robolectric downloads its Android test runtime.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
