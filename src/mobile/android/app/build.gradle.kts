import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val modelsDir = providers.environmentVariable("STENOKOT_MODELS_DIR")
    .orElse(rootProject.layout.projectDirectory.dir("../../../models").asFile.absolutePath)
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "ru.stenokot.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "ru.stenokot.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        providers.gradleProperty("appSuffix").orNull?.let { suffix -> applicationIdSuffix = suffix }
    }

    flavorDimensions += "models"
    productFlavors {
        create("dev") {
            dimension = "models"
            buildConfigField("boolean", "MODELS_IN_ASSETS", "false")
        }
        create("full") {
            dimension = "models"
            buildConfigField("boolean", "MODELS_IN_ASSETS", "true")
        }
    }

    signingConfigs {
        keystoreProps?.let { props ->
            create("team") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("team") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("onnx", "gguf", "txt", "json")
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

val syncModelAssets = tasks.register<Sync>("syncModelAssets") {
    from(modelsDir) {
        include("asr-ru/**", "asr-en/**", "vad/**", "diar/**", "llm/**")
    }
    into(layout.buildDirectory.dir("generated/modelAssets/models"))
}

androidComponents {
    onVariants(selector().withFlavor("models" to "full")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            layout.buildDirectory.dir("generated/modelAssets").get().asFile.absolutePath,
        )
    }
}

tasks.configureEach {
    if (name.endsWith("Assets") && name.contains("Full")) dependsOn(syncModelAssets)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine-llm"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
