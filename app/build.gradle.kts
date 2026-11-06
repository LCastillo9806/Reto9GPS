plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "co.edu.unal.reto9gps"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "co.edu.unal.reto9gps"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // ViewBinding: acceso seguro a las vistas del XML (binding.map, binding.toolbar...)
        viewBinding = true
    }
}

dependencies {

    // ==========================================
    // DEPENDENCIAS BASE DE ANDROID
    // ==========================================
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    // ==========================================
    // RETO 9: GPS + MAPA + PUNTOS DE INTERÉS
    // ==========================================

    // osmdroid: dibuja el mapa de OpenStreetMap, marcadores y el círculo del radio
    implementation(libs.osmdroid.android)

    // Preferencias: pantalla de Ajustes para configurar el radio en km
    implementation(libs.androidx.preference.ktx)

    // lifecycleScope + corrutinas: consultas a internet sin congelar la pantalla
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // El GPS se lee con LocationManager, que ya viene incluido en Android:
    // no hace falta ninguna librería de Google.

    // ==========================================
    // PRUEBAS
    // ==========================================
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
