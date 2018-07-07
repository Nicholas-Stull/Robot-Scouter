import org.jetbrains.kotlin.gradle.internal.CacheImplementation

android {
    buildTypes {
        getByName("release") {
            postprocessing {
                consumerProguardFile("proguard-rules.pro")
            }
        }
    }
}

androidExtensions {
    defaultCacheImplementation = CacheImplementation.NONE
}

dependencies {
    api(project(":library:core"))

    api(Config.Libs.Jetpack.fragment)
    api(Config.Libs.Jetpack.material)
    api(Config.Libs.Jetpack.emoji)
    api(Config.Libs.Jetpack.constraint)
    api(Config.Libs.FirebaseUi.firestore)
    api(Config.Libs.Anko.design)
    api(Config.Libs.Anko.appCompat)

    implementation(Config.Libs.Jetpack.browser)
    implementation(Config.Libs.Jetpack.pref)
}
