plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.camimu.orient.replay.ReplayMainKt") }

// Relative paths passed via --args resolve from the repository root.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
