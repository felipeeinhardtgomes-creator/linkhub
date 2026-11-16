plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "br.com.ppci.levantamento"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.ppci.levantamento"
        // PdfRenderer existe desde a API 21, mas 26+ cobre tablets de campo atuais
        // e dá FileProvider/Canvas estáveis para o motor de tiles.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Fontes de teste: PDFs A1/A0 reais vão em src/androidTest/assets (não versionar PDFs de clientes).
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Esquemas do Room versionados -> migrações testáveis (dado de campo nunca pode ser perdido).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // ---- Base / UI ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- DI ----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ---- Persistência 100% offline ----
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore)          // dados da empresa, último responsável etc.
    implementation(libs.kotlinx.serialization.json)  // catálogo de subtipos/preços em JSON de assets

    // ---- Async ----
    implementation(libs.kotlinx.coroutines.android)

    // ---- Fotos ----
    implementation(libs.coil.compose)                // miniaturas com cache em disco
    implementation(libs.androidx.exifinterface)      // corrige rotação das fotos da câmera

    // ---- Leitura do carimbo da prancha (cliente/obra/responsável) ----
    implementation(libs.pdfbox.android)              // texto vetorial do PDF do AutoCAD
    implementation(libs.mlkit.text.recognition)      // OCR offline, fallback

    // ---- Renderização de PDF ----
    // Sem dependência: android.graphics.pdf.PdfRenderer (tiles) e PdfDocument (relatório).

    // ---- Testes ----
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
