import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

val mediaSmokeRuntime = configurations.create("mediaSmokeRuntime")
dependencies {
    add(mediaSmokeRuntime.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation("ca.weblite:webview:1.13.0")
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        val localPlatform = providers.gradleProperty("localPlatformPath").orNull
        if (localPlatform != null) local(localPlatform) else intellijIdea("2026.3")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = "1.2.3"
        ideaVersion { sinceBuild = "263"; untilBuild = "263.*" }
    }
}

kotlin { jvmToolchain(25) }

// Opt-in integration check of the actual OS browser and decoder, outside the IDE process.
tasks.register<JavaExec>("smokeTestMedia") {
    group = "verification"
    description = "Verify native H.264 playback and page retention using a local test video"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + mediaSmokeRuntime
    mainClass = "com.github.zakgrivell.invadetheburg.NativeMediaSmoke"
    systemProperty("java.awt.headless", "false")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("evaluateEta") {
    group = "verification"
    description = "Evaluate ETA baselines from <logDir>/stretches.csv (chronological split; seed is accepted but unused)"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.github.zakgrivell.invadetheburg.eta.EtaEvaluationMain"
    args(providers.gradleProperty("eta.logDir").orElse("src/test/resources/eta").get())
    providers.gradleProperty("eta.seed").orNull?.let { args(it) }
}
