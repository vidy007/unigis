// Standalone build for the dependency-free variant; see build-apk.sh.
rootProject.name = "unigis-lite"

dependencyResolutionManagement {
    // Same Kotlin version as the main app.
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
