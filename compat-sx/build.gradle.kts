plugins { `java-library` }

dependencies {
    implementation("org.openjdk.nashorn:nashorn-core:15.4")
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
}
