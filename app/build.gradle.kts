import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// La clave de xeno-canto (cantos), opcional, en local.properties; también en Ajustes.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localString(key: String) = "\"" + (local.getProperty(key) ?: "").replace("\"", "") + "\""

android {
    namespace = "es.altamirano.bird"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.altamirano.bird"
        minSdk = 31          // Android 12
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.1"
        // Los móviles de hoy (y los Pixel de GrapheneOS) son arm64: el motor de LiteRT sólo para ese.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "XENO_CANTO_KEY", localString("xenocanto.apiKey"))
    }

    // La clave de release vive fuera del repositorio; sin ella la release sale sin firmar.
    val keyFile = File(System.getProperty("user.home"), ".android/claves/bird-release.properties")
    val releaseKey = if (keyFile.exists()) Properties().apply { keyFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Los modelos se leen mapeados en memoria: sin comprimir dentro del APK.
    androidResources { noCompress += listOf("tflite", "bin") }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.litert)
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
}

// Los modelos no están en el repositorio: se bajan con Tools/descargar-modelos.sh.
val checkModels by tasks.registering {
    doLast {
        val dir = file("src/main/assets/models")
        val needed = listOf("birdnet.tflite", "aiy_birds_v1.tflite", "magic_touch.tflite", "meta_weights.bin", "labels.txt")
        val missing = needed.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) throw GradleException("Faltan los modelos ($missing): ejecuta Tools/descargar-modelos.sh")
    }
}
tasks.named("preBuild") { dependsOn(checkModels) }
