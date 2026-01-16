plugins {
    id("java")
    `java-library`
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    compileOnly(libs.bundles.netty)

    implementation(libs.fastutil)
    api(libs.jspecify)
}