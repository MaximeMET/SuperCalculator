plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Symja —— 与原版同源的符号计算内核（GPL-3.0）
    //
    // 这里刻意锁定到上游 tag `version_2016-04-15`：参考 App 用的就是这一版。
    // 换用新版会让 TeX 排版器输出改变（\ln{x} 变成 \log (x)、\cdot 间距不同等），
    // 差分测试的按钮结果一致率会从预期的高位掉到 60% 出头。
    implementation(files("libs/symja-2016-04-15.jar"))

    // Symja 内置的 JAS 用 log4j 1.x 打日志。这里用 log4j-over-slf4j 提供 API，
    // 而不是直接打包 log4j 1.2.11（那个版本有已知 CVE，而且我们并不需要真的落日志）。
    implementation(files("libs/log4j-over-slf4j-1.7.2.jar"))
    implementation("org.slf4j:slf4j-api:1.7.36")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// 调试用：打印编译期依赖的 jar 路径
tasks.register("printClasspath") {
    val cp = configurations.named("compileClasspath")
    doLast {
        cp.get().files.forEach { println(it.absolutePath) }
    }
}
