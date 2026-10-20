pluginManagement {
    repositories {
        // 阿里云镜像优先（国内提速）；官方仓库兜底——CI（GitHub Actions）冷缓存时
        // 阿里云镜像可能缺失部分插件标记（如 com.google.devtools.ksp），自动回退到官方源
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()      // 阿里云镜像缺失时兜底（CI 冷缓存场景）
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Personal Overtime Record"
include(":app")
