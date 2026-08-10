plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 发布签名。口令和 keystore 路径都从**用户级** `~/.gradle/gradle.properties` 读，
 * 仓库里既没有 keystore 也没有口令。
 *
 * 为什么 debug 也用它签（T-2026-08-06-006）：Android 拒绝用不同签名的包做原地升级。
 * 采集期间必然会修 bug 重新发包，如果签名变了，参与者必须先卸载——
 * **而卸载会带走本地还没上传的录音**。所以发给参与者的每一个包，
 * 不管是 debug 还是 release，都必须用同一个密钥。
 */
val egoKeystoreFile: String? = (project.findProperty("EGOAUDIO_KEYSTORE_FILE") as String?)
    ?: System.getenv("EGOAUDIO_KEYSTORE_FILE")
val egoKeystorePassword: String? = (project.findProperty("EGOAUDIO_KEYSTORE_PASSWORD") as String?)
    ?: System.getenv("EGOAUDIO_KEYSTORE_PASSWORD")
val egoKeyAlias: String? = (project.findProperty("EGOAUDIO_KEY_ALIAS") as String?)
    ?: System.getenv("EGOAUDIO_KEY_ALIAS")
val egoKeyPassword: String? = (project.findProperty("EGOAUDIO_KEY_PASSWORD") as String?)
    ?: System.getenv("EGOAUDIO_KEY_PASSWORD")
val egoSigningAvailable = egoKeystoreFile != null && file(egoKeystoreFile!!).exists() &&
    egoKeystorePassword != null && egoKeyAlias != null && egoKeyPassword != null

android {
    namespace = "com.example.nunarecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nunarecorder"
        minSdk = 26
        targetSdk = 36
        // 每次对外发包必须 +1，否则 Android 不认为是升级
        versionCode = 23
        versionName = "1.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * 参与者包只带 arm ABI；模拟器包保留全部。
     *
     * 106 MB 里 x86 + x86_64 占 51.7 MB，而没有任何参与者手机需要它们——真机都是 arm。
     * 它们在包里只是因为我要跑模拟器。30 个参与者各自的手机流量是实打实的成本。
     *
     * 不用一刀切 abiFilters：那会把我自己的模拟器测试环境弄没了，
     * 而模拟器验证过深色模式、状态机、退避序列这些真机不方便反复测的东西。
     */
    flavorDimensions += "target"
    productFlavors {
        create("device") {
            dimension = "target"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("emulator") {
            dimension = "target"
            // 不设 abiFilters = 保留全部，x86_64 模拟器才装得上
        }
    }

    signingConfigs {
        if (egoSigningAvailable) {
            create("egoaudio") {
                storeFile = file(egoKeystoreFile!!)
                storePassword = egoKeystorePassword
                keyAlias = egoKeyAlias
                keyPassword = egoKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (egoSigningAvailable) signingConfig = signingConfigs.getByName("egoaudio")
        }
        debug {
            // 没有配置密钥时退回 Android 默认 debug keystore，本机开发不受影响；
            // 但那样签出来的包**不能**发给参与者。
            if (egoSigningAvailable) signingConfig = signingConfigs.getByName("egoaudio")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // 纯逻辑类偶尔会调 android.util.Log（只在异常分支）。默认行为是抛
            // "not mocked"，会把一个本该通过的用例变成假失败。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.concentus)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.onnxruntime.android)
    implementation(libs.play.services.location)
    // 扫码入组：CameraX 预览 + ML Kit 条码识别（bundled，不依赖 Play 服务下发模型）
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}