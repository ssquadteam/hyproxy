plugins {
    id("java")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    compileOnly(files("libs/HytaleServer.jar"))
}


tasks.shadowJar {
    archiveFileName.set("hyproxy-backend-$version.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
