plugins { `java-library` }

dependencies {
    api(project(":core"))
    implementation("org.openjdk.nashorn:nashorn-core:15.4")
    // Bukkit's YAML serializer is part of the input language's observable behavior.
    // Server types do not escape this compatibility module into the core.
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
}

tasks.test {
    providers.gradleProperty("niReferenceJar").orNull?.let { systemProperty("niReferenceJar", it) }
    providers.gradleProperty("niConfigRoot").orNull?.let { systemProperty("niConfigRoot", it) }
}
