// 仓库策略按环境分流：
// - 本地（国内网络）：阿里云镜像优先提速，官方仓库兜底
// - CI（GitHub Actions，海外 runner）：仅用官方仓库。实测阿里云镜像从海外 runner
//   解析插件标记会失败（2026-10-17 切换镜像后 CI 全部失败，报 could not resolve plugin
//   artifact；而 09-04 ~ 10-02 官方仓库配置下全部成功，且把官方仓库追加在镜像之后
//   作兜底也无法恢复），故 CI 直连官方源。
// GitHub Actions 会为所有步骤注入 CI=true 环境变量

pluginManagement {
    // 注意：pluginManagement 块独立编译，无法引用脚本顶层 val，CI 判断需在此内联
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (System.getenv("CI") != null) {
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            maven { url = uri("https://jitpack.io") }
        }
    }
}

rootProject.name = "Personal Overtime Record"
include(":app")
