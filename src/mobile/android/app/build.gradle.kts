import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Проверка выполняется без скачивания: APK обязан содержать проверенную модель.
val modelManifest = file("src/main/assets/model-manifest.properties")
val vadManifest = file("src/main/assets/vad-manifest.properties")
val llmManifest = file("src/main/assets/llm-manifest.properties")
val llmFastManifest = file("src/main/assets/llm-fast-manifest.properties")
val diarizationManifest = file("src/main/assets/diarization-manifest.properties")
val sherpaManifest = rootProject.file(
    if (providers.gradleProperty("ctcPrototype").orNull == "true") ".model-cache/ctc-prototype.properties"
    else "dependencies/sherpa-onnx.properties",
)
val sherpa = Properties().apply { sherpaManifest.inputStream().use { load(it) } }
val sherpaAar = rootProject.file("dependencies/${sherpa.getProperty("file")}")
val verifyBundledModels = tasks.register("verifyBundledModels") {
    val manifestFile = modelManifest
    val vadManifestFile = vadManifest
    val llmManifestFile = llmManifest
    val llmFastManifestFile = llmFastManifest
    val diarizationManifestFile = diarizationManifest
    val libraryFile = sherpaAar
    val libraryHash = sherpa.getProperty("sha256")
    val assetsDirectory = layout.projectDirectory.dir("src/main/assets").asFile
    inputs.file(modelManifest)
    inputs.file(vadManifest)
    inputs.file(llmManifest)
    inputs.file(llmFastManifest)
    inputs.file(diarizationManifest)
    inputs.file(sherpaManifest)
    inputs.files(fileTree("src/main/assets/models"))
    inputs.files(sherpaAar)
    val licenseManifest = rootProject.file("dependencies/licenses.properties")
    inputs.file(licenseManifest)
    inputs.files(fileTree("src/main/assets/third-party"))
    doLast {
        fun verify(path: File, expected: String, size: Long? = null) {
            check(path.isFile) {
                "Отсутствует ${path.name}. Выполните python3 scripts/prepare_models.py из корня проекта."
            }
            check(size == null || path.length() == size) { "Неверный размер: $path" }
            val hash = MessageDigest.getInstance("SHA-256")
            path.inputStream().buffered().use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == expected) {
                "Повреждён файл: $path. Повторите python3 scripts/prepare_models.py."
            }
        }
        verify(libraryFile, libraryHash)
        listOf(manifestFile, vadManifestFile, llmManifestFile, llmFastManifestFile, diarizationManifestFile).forEach { modelFile ->            val manifest = Properties().apply { modelFile.inputStream().use { load(it) } }
            manifest.getProperty("files").split(',').forEach { name ->
                verify(
                    File(assetsDirectory, "${manifest.getProperty("model.directory")}/$name"),
                    manifest.getProperty("$name.sha256"),
                    manifest.getProperty("$name.size").toLong(),
                )
            }
        }
        val licenses = Properties().apply { licenseManifest.inputStream().use { load(it) } }
        licenses.getProperty("files").split(',').forEach { name ->
            verify(File(assetsDirectory, "third-party/$name"), licenses.getProperty("$name.sha256"))
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyBundledModels) }

android {
    namespace = "org.cfm.catscriptor"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.cfm.catscriptor"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The target phones are 64-bit ARM; other ABIs only inflate the APK.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    androidResources {
        // Stored uncompressed so large models need no inflating when read from assets.
        noCompress += listOf("bin", "gguf", "onnx")
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
