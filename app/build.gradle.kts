import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // id("com.google.gms.google-services") // 已禁用云同步
    id("com.google.devtools.ksp")
}

// 版本号自动管理
// 优先级：CI 传入（git tag，如 -PciVersionName=1.3.0 -PciVersionCode=10300） > 本地 version.properties 自动递增
// versionName 为三段格式（如 0.1.4）；versionCode 用递增构建号（BUILD_NUMBER）
// 注意：变量必须加 app 前缀，避免与 defaultConfig 的属性（versionCode/versionName）同名被遮蔽，
// 否则 defaultConfig 内赋值时右侧会解析成 self-assignment，导致 APK 版本号为空（与下方签名配置同理）
val versionPropsFile = file("version.properties")
var appVersionCode = 1
var appVersionName = "0.1.9"

val ciVersionName = project.findProperty("ciVersionName") as String?
val ciVersionCode = project.findProperty("ciVersionCode") as String?

// 仅在实际产出 APK 的任务（assemble/bundle/install/package）中自动递增构建号；
// clean/test/lint/编译/IDE 同步等不修改 version.properties，
// 避免"任何 Gradle 调用都在配置期改写文件"导致的 git 树常脏与增量缓存失效
val producesArtifact = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("assemble", ignoreCase = true) ||
        taskName.contains("bundle", ignoreCase = true) ||
        taskName.contains("install", ignoreCase = true) ||
        taskName.contains("package", ignoreCase = true)
}

if (!ciVersionName.isNullOrBlank() && !ciVersionCode.isNullOrBlank()) {
    // CI（GitHub Actions）根据 git tag 传入版本号，不修改 version.properties
    appVersionCode = ciVersionCode.toInt()
    appVersionName = ciVersionName
    println("🔢 CI version from tag: $appVersionName (build #$appVersionCode)")
} else if (versionPropsFile.exists()) {
    val props = Properties()
    versionPropsFile.inputStream().use { props.load(it) }
    val buildNumber = props.getProperty("BUILD_NUMBER", "1").toInt()
    val baseVersion = props.getProperty("BASE_VERSION", "0.1.9")
    
    // 递增构建号
    val newBuildNumber = if (producesArtifact) buildNumber + 1 else buildNumber
    if (newBuildNumber != buildNumber) {
        props.setProperty("BUILD_NUMBER", newBuildNumber.toString())
        versionPropsFile.outputStream().use { props.store(it, "Auto-incremented build number") }
    }
    
    appVersionCode = newBuildNumber
    // versionName 只保留三段基础版本号（如 0.1.4），不再追加构建号
    appVersionName = baseVersion
    
    println("🔢 Version: $appVersionName (build #$appVersionCode)" + if (producesArtifact) "" else "，本次调用不产出 APK，未递增构建号")
} else {
    // 首次创建版本文件
    val props = Properties()
    props.setProperty("BASE_VERSION", "0.1.10")
    props.setProperty("BUILD_NUMBER", "1")
    versionPropsFile.outputStream().use { props.store(it, "Initial version properties") }

    appVersionCode = 1
    appVersionName = "0.1.10"
    
    println("🔢 Version initialized: $appVersionName (build #$appVersionCode)")
}

// 签名配置：仅当 CI 注入环境变量（GitHub Secrets）时启用，本地构建保持不签名/自动签名，不受影响
// 注意：变量名加 env 前缀，避免与 SigningConfig 的属性（keyAlias/keyPassword 等）同名，
// 否则 create("release") 内赋值时右侧会被遮蔽成 self-assignment，导致签名配置缺属性
val envKeystoreBase64 = System.getenv("KEYSTORE_BASE64")
val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias = System.getenv("KEY_ALIAS")
val envKeyPassword = System.getenv("KEY_PASSWORD")
val hasSigningEnv = !envKeystoreBase64.isNullOrBlank() && !envKeystorePassword.isNullOrBlank()
        && !envKeyAlias.isNullOrBlank() && !envKeyPassword.isNullOrBlank()

android {
    namespace = "com.example.personalovertimerecord"
    compileSdk = 36

    signingConfigs {
        if (hasSigningEnv) {
            create("release") {
                // 把 Secrets 里的 base64 keystore 解码到构建目录（临时文件，不入库）
                val keystoreFile = layout.buildDirectory.file("release.keystore").get().asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(envKeystoreBase64))
                storeFile = keystoreFile
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                // minSdk=24 时 AGP 默认只打 v2 签名；显式开启 v1（JAR）签名，
                // 保证旧版签名读取路径/第三方工具也能校验，v2/v3 保持默认开启
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.personalovertimerecord"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // CI 注入 release 签名环境时，让 debug 包与 release 包使用同一把签名：
            // 这样已安装的 debug 版可以被 release 版直接覆盖升级（Android 要求同签名才能覆盖安装）。
            // 本地 Android Studio 运行（无签名环境变量）仍走默认 debug 签名，不受影响。
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // R8 代码缩减与混淆：减小 APK 体积；Gson/SQLCipher/Room 等 keep 规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI 注入签名环境变量时使用 release 签名；本地构建仍为未签名/调试行为
            if (hasSigningEnv) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }

    // APK 输出文件名带上版本号与构建号：
    // PersonalOvertimeRecord_v{versionName}_{versionCode/构建号}_{buildType}.apk
    // （CI 通过 -PciVersionName/-PciVersionCode 传参时使用 tag 版本；本地构建每次自动递增构建号）
    applicationVariants.all {
        val buildTypeName = name
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName =
                "PersonalOvertimeRecord_v${appVersionName}_${appVersionCode}_${buildTypeName}.apk"
        }
    }
}

// Room 导出数据库 schema 到 app/schemas/（随代码提交，供迁移测试与升级校验）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 修改日志内置：根目录 CHANGELOG.md 为单一事实源，构建期拷入 assets 供应用内离线查看
tasks.register("copyChangelog") {
    inputs.file(rootProject.file("CHANGELOG.md"))
    outputs.file("$projectDir/src/main/assets/changelog.md")
    doLast {
        val target = file("$projectDir/src/main/assets/changelog.md")
        target.parentFile.mkdirs()
        rootProject.file("CHANGELOG.md").copyTo(target, overwrite = true)
    }
}
tasks.named("preBuild").configure { dependsOn("copyChangelog") }



dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation(libs.androidx.fragment)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.mpchart)
    
    // Gson - JSON 序列化/反序列化
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")
    
    // SQLCipher - Database Encryption
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.2.0")
    
    // Biometric Authentication
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    
    // WebDAV will use built-in HttpURLConnection
    
    // WorkManager - 后台周期任务（自动同步，替代 AlarmManager，兼容 Doze 与重启恢复）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    
    // Firebase dependencies - 已禁用云同步
    // implementation(libs.firebase.auth.ktx)
    // implementation(libs.firebase.firestore.ktx)
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
