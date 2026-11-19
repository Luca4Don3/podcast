import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.FilterConfiguration
import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Linux-style release number. Android's independent upgrade counter always increases.
val podcastVersion = "1.6.0"
val podcastVersionCode = 18
require(podcastVersion.matches(Regex("(?:0|[1-9][0-9]*)[.](?:0|[1-9][0-9]*)(?:[.](?:0|[1-9][0-9]*)|-rc[1-9][0-9]*)?"))) {
    "Expected Linux-style podcast version (for example 1.4, 1.4.1 or 1.5-rc1)"
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.podcastapp"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.podcastapp"
        // 最低安装要求：Android 7.0（API 24）；实际机型表现以验收为准。
        minSdk = 24
        targetSdk = 35
        versionCode = podcastVersionCode
        versionName = podcastVersion

        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=none" }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val apiBase = providers.gradleProperty("podcast.defaultApiBaseUrl").orNull
            ?: localProperties.getProperty("podcast.defaultApiBaseUrl", "")
        val apiBaseLiteral = apiBase.trim().replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"" + apiBaseLiteral + "\"")

        // 只保留中英文资源，减小包体并避免设备语言回退异常
        resourceConfigurations += listOf("zh", "en")
    }

    // 按 ABI 拆分：armeabi-v7a 老机型 / arm64-v8a 新机型各拿到更小的包
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // 可侧载的精简测试包：采用 release 的 R8/资源裁剪、沿用调试包身份；
        // 不带 Compose 调试工具，也不更改正式 release 的签名方式。
        create("compact") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all { test ->
            providers.gradleProperty("podcast.testNativeLibDir").orNull?.takeIf { it.isNotBlank() }?.let { dir ->
                test.systemProperty("java.library.path", dir)
                test.systemProperty("podcast.requireNative", "true")
            }
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

abstract class ExportPodcastApks : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val releaseVersion: Property<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Internal
    abstract val apkLoader: Property<BuiltArtifactsLoader>

    @TaskAction
    fun export() {
        val input = apkDirectory.get()
        val version = releaseVersion.get()
        val outputs = checkNotNull(apkLoader.get().load(input)) { "APK output metadata is missing" }.elements
        check(outputs.count { it.filters.isEmpty() } == 1) { "Expected a universal APK" }
        val published = mutableSetOf<String>()
        for (output in outputs) {
            check(output.versionName == version) { "APK version does not match export version" }
            val abiDir = output.filters.singleOrNull()?.let {
                check(it.filterType == FilterConfiguration.FilterType.ABI) { "Unexpected APK filter" }
                it.identifier.also { abi -> check(abi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) }
            }.orEmpty()
            check(output.filters.size <= 1 && published.add(abiDir)) { "Duplicate or unexpected APK output" }
            val from = input.asFile.resolve(output.outputFile)
            val to = destination.get().asFile.resolve(abiDir).resolve("podcast-$version.apk")
            from.copyTo(to, overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val artifact = variant.artifacts
        tasks.register<ExportPodcastApks>("export${variantName.replaceFirstChar { it.uppercaseChar() }}Apks") {
            group = "build"
            description = "Export $variantName APKs as podcast-$podcastVersion.apk"
            apkDirectory.set(artifact.get(SingleArtifact.APK))
            apkLoader.set(artifact.getBuiltArtifactsLoader())
            releaseVersion.set(podcastVersion)
            destination.set(layout.buildDirectory.dir("outputs/podcast/$variantName/$podcastVersion"))
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.media3.exoplayer)
    implementation("androidx.media3:media3-exoplayer-hls:${libs.versions.media3.get()}")
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
