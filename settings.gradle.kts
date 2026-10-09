pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "ItemLoom"
include("core", "compat-ni", "compat-sx", "paper26")
include("keystone-runtime")
project(":keystone-runtime").projectDir = file("vendor/keystone")
