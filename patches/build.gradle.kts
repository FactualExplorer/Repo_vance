group = "app.revanced"

patches {
    about {
        name = "ReVanced Patches (playback fork)"
        description = "ReVanced patches with backported YouTube playback and quality-selection fixes"
        source = "https://github.com/FactualExplorer/Repo_vance"
        author = "ReVanced"
        contact = "patches@revanced.app"
        website = "https://revanced.app"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}

publishing {
    repositories {
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches")
            credentials(PasswordCredentials::class)
        }
    }
}

apply(from = "strings-processing.gradle.kts")
