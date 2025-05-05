plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.layer.i2c"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.layer"
                artifactId = "i2c"
                version = "1.0.7"
                
                pom {
                    name.set("Layer I2C Library")
                    description.set("Android library for I2C communication with various sensors including AS7341/AS7343 spectral sensors and SHT40 temperature/humidity sensor")
                    url.set("https://github.com/layerproject/layer-android-i2c-library")
                    
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set("layerproject")
                            name.set("Layer")
                            email.set("gilles@layer.com")
                        }
                    }
                    
                    scm {
                        connection.set("scm:git:git://github.com/layerproject/layer-android-i2c-library.git")
                        developerConnection.set("scm:git:ssh://github.com/layerproject/layer-android-i2c-library.git")
                        url.set("https://github.com/layerproject/layer-android-i2c-library")
                    }
                }
            }
        }
        
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/layerproject/layer-android-i2c-library")
                credentials {
                    username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USER")
                    password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}