plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cn.jiangcheng.lemon"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }
    defaultConfig {
        applicationId = "cn.jiangcheng.lemon"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.9-alpha"
        testInstrumentationRunner = "cn.jiangcheng.lemon.SmokeInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val signingPath=System.getenv("LEMON_SIGNING_STORE")
    val releaseSigning=if(!signingPath.isNullOrBlank())signingConfigs.create("publicRelease") {
        storeFile=file(signingPath)
        storePassword=System.getenv("LEMON_SIGNING_STORE_PASSWORD") ?: error("Missing signing store password")
        keyAlias=System.getenv("LEMON_SIGNING_ALIAS") ?: error("Missing signing alias")
        keyPassword=System.getenv("LEMON_SIGNING_KEY_PASSWORD") ?: error("Missing signing key password")
    }else null
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = releaseSigning ?: if(providers.gradleProperty("lemon.alphaSigning").orNull=="true")signingConfigs.getByName("debug")else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
        }
    }
}
dependencies { implementation("com.google.mlkit:text-recognition-chinese:16.0.1"); implementation("io.noties.markwon:core:4.6.2"); implementation("io.noties.markwon:ext-tables:4.6.2"); implementation("io.noties.markwon:ext-strikethrough:4.6.2"); implementation("androidx.core:core:1.13.1"); implementation("org.apache.commons:commons-compress:1.28.0"); implementation("org.tukaani:xz:1.10"); testImplementation("junit:junit:4.13.2") }
