import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

/*
  发布签名：密钥和口令都放在仓库外的 `keystore.properties` 里（已 gitignore）。
  文件不存在时整个签名配置不生效，`assembleRelease` 照旧出 unsigned 包——
  别人 clone 下来不需要密钥也能构建。
*/
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystoreFile = releaseKeystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it) }
val hasReleaseSigning = releaseKeystoreFile?.exists() == true

android {
    namespace = "io.github.maximemet.supercalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.maximemet.supercalc"
        minSdk = 21
        targetSdk = 35
        // 本地真机测试包，没有对外发布。每出一版测试包就把 versionCode 加一，
        // 省得手机上装不上（同名同号只能覆盖安装，用户分不出新旧）。
        versionCode = 27
        versionName = "0.1.2-dev25"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                // v1 留给 API 21-23（minSdk 21 必须带），v2/v3 给新系统
                enableV1Signing = true
                enableV2Signing = true
                // v3 现在用不上，但开了以后才有换密钥（rotation）的余地
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 刻意不混淆：引擎靠反射加载符号，而且首版发布以可复现为先
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Symja 2016 用到 java.util.function 之类 Java 8 的 API
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        viewBinding = true
        // 关于页要显示版本号（参考实现也是读 BuildConfig.VERSION_NAME）
        buildConfig = true
    }

}

dependencies {
    implementation(project(":engine"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation(kotlin("test"))
}

/*
  javac 兼容补丁（不改产物，只是换个喂 classpath 的方式）。

  某些受限环境会把 `Path.toRealPath()` 拦掉，而 JDK17 的 javac 收尾时要调它：
  结果是 classpath 里的**目录**项解析不出来（"package 不存在"），关闭 jar 时还会
  抛一次 AccessDenied。两者叠加，报错看上去就是「找不到 Kotlin 产物里的类」。

  绕法两条：把 Kotlin 产物先打成 jar 再交给 javac（jar 读得动，只剩收尾那声
  异常），以及 fork 成独立进程跑 javac——它自己返回 0，异常只是 stderr 噪音。
  普通机器上这么构建也完全没问题，只是 java 编译多起一个进程。
*/
fun kotlinClassesJarFor(variant: String): TaskProvider<Jar> =
    tasks.register<Jar>("kotlinClassesJarForJavac${variant.replaceFirstChar { it.uppercase() }}") {
        dependsOn("compile${variant.replaceFirstChar { it.uppercase() }}Kotlin")
        archiveFileName.set("kotlin-classes-$variant.jar")
        destinationDirectory.set(layout.buildDirectory.dir("tmp/javac-kotlin-jar/$variant"))
        from(layout.buildDirectory.dir("tmp/kotlin-classes/$variant"))
    }

val kotlinClassesJarDebug = kotlinClassesJarFor("debug")
val kotlinClassesJarRelease = kotlinClassesJarFor("release")

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.isFork = true
    options.forkOptions.executable = "${System.getProperty("java.home")}/bin/javac.exe"
}

// classpath 要等 AGP 配完才有值，所以放到 afterEvaluate 里换
afterEvaluate {
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        val kotlinJar = if (name.contains("Release")) kotlinClassesJarRelease else kotlinClassesJarDebug
        dependsOn(kotlinJar)
        classpath = files(kotlinJar) +
            classpath.filter { !it.path.contains("kotlin-classes") }
    }
}
