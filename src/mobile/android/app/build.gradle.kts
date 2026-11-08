import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Кладёт модели в assets сборки full. Выход задачи объявлен, поэтому её видят все потребители assets. */
abstract class SyncModelAssets : DefaultTask() {
    @get:Inject
    abstract val files: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val models: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun sync() {
        files.sync {
            from(models) { include("asr-ru/**", "asr-en/**", "vad/**", "diar/**", "llm/**") }
            into(output.dir("models"))
        }
    }
}

val modelsDir: Provider<String> = providers.environmentVariable("STENOKOT_MODELS_DIR")
    .orElse(rootProject.layout.projectDirectory.dir("../../../models").asFile.absolutePath)
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

val modelsLock = providers.fileContents(rootProject.layout.projectDirectory.file("../../../config/models.lock"))
    .asText.orElse("")
    .map { text -> text.lines().filter(String::isNotBlank).joinToString("|") }
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

android {
    namespace = "ru.stenokot.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "ru.skvor.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        providers.gradleProperty("appSuffix").orNull?.let { suffix -> applicationIdSuffix = suffix }
        buildConfigField("String", "MODELS_LOCK", "\"${modelsLock.get()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit.get()}\"")
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
        /** Как релиз, но с демо-состояниями: на ней замеряется скорость прокрутки. */
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("perf") {
            kotlin.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
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

val syncModelAssets = tasks.register<SyncModelAssets>("syncModelAssets") {
    models.set(layout.projectDirectory.dir(modelsDir.get()))
}

androidComponents {
    onVariants(selector().withFlavor("models" to "full")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncModelAssets, SyncModelAssets::output)
    }
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
