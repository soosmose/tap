import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.md_5.specialsource.Jar
import net.md_5.specialsource.JarMapping
import net.md_5.specialsource.JarRemapper
import net.md_5.specialsource.provider.JarProvider
import net.md_5.specialsource.provider.JointProvider
import java.io.File
import java.io.OutputStream.nullOutputStream
import org.gradle.jvm.tasks.Jar as GradleJar

plugins {
    kotlin("jvm") version "1.5.20"
    id("com.github.johnrengelman.shadow") version "7.0.0"
    `maven-publish`
}

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("net.md-5:SpecialSource:1.10.0")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
    }
}

allprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://papermc.io/repo/repository/maven-public/")
        maven(url = "https://repo.dmulloy2.net/repository/public/")
        mavenLocal()
    }

    dependencies {
        compileOnly(kotlin("stdlib"))
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
        compileOnly("com.comphenix.protocol:ProtocolLib:4.7.0-SNAPSHOT")
//        compileOnly(rootProject.fileTree("dir" to "libs", "include" to "*.jar"))

        implementation("org.mariuszgromada.math:MathParser.org-mXparser:4.4.2")

        testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
        testImplementation("org.junit.jupiter:junit-jupiter-engine:5.7.0")
        testImplementation("org.mockito:mockito-core:3.6.28")
        testImplementation("org.spigotmc:spigot:1.17-R0.1-SNAPSHOT:remapped-mojang")
    }

    tasks {
        test {
            useJUnitPlatform()
        }
    }
}

project(":paper") {
    dependencies {
        implementation(project(":api"))

        subprojects.filter { it.name != path }.forEach { subproject ->
            implementation(subproject)
        }
    }
}

subprojects {
    if (path in setOf(":api", ":paper")) {
        // setup api & test plugin
        dependencies {
            compileOnly("io.papermc.paper:paper-api:1.17-R0.1-SNAPSHOT")

            testImplementation("io.papermc.paper:paper-api:1.17-R0.1-SNAPSHOT")
        }
    } else {
        configurations {
            create("mojangMapping")
            create("spigotMapping")
        }

        //setup nms
        dependencies {
            implementation(project(":api"))
        }

        tasks {
            jar {
                doLast {
                    val archiveFile = archiveFile.get().asFile
                    val mojangOutput = File(archiveFile.parentFile, "remapped-mojang.jar")
                    val spigotOutput = File(archiveFile.parentFile, "remapped-spigot.jar")

                    val mojangMapping = configurations.named("mojangMapping").get().firstOrNull()
                    val spigotMapping = configurations.named("spigotMapping").get().firstOrNull()

                    if (mojangMapping != null && spigotMapping != null) {
                        var inputJar = Jar.init(archiveFile)
                        val mojang = JarMapping()
                        mojang.loadMappings(mojangMapping.canonicalPath, true, false, null, null)

                        val mojangProvider = JointProvider()
                        mojangProvider.add(JarProvider(inputJar))
                        mojang.setFallbackInheritanceProvider(mojangProvider)

                        val mojangRemapper = JarRemapper(mojang)
                        mojangRemapper.remapJar(inputJar, mojangOutput)
                        inputJar.close()

                        inputJar = Jar.init(mojangOutput)
                        val spigot = JarMapping()
                        spigot.loadMappings(spigotMapping)

                        val spigotProvider = JointProvider()
                        spigotProvider.add(JarProvider(inputJar))
                        spigot.setFallbackInheritanceProvider(spigotProvider)

                        val spigotRemapper = JarRemapper(spigot)
                        spigotRemapper.remapJar(inputJar, spigotOutput)
                        inputJar.close()

                        archiveFile.writeBytes(spigotOutput.readBytes())
                        mojangOutput.delete()
                        spigotOutput.delete()
                    } else {
                        logger.warn("Mojang and Spigot mapping should be specified for ${path.drop(1).takeWhile { it != ':' }}.")
                    }
                }
            }
        }
    }
}

dependencies {
    subprojects {
        implementation(this)
    }
}

tasks {
    jar {
        subprojects.filter { it.name != ":paper" }.forEach { subproject ->
            from(subproject.sourceSets["main"].output)
        }
    }
    create<GradleJar>("sourcesJar") {
        archiveClassifier.set("sources")
        for (subproject in subprojects) {
            from(subproject.sourceSets["main"].allSource)
        }
    }
    shadowJar {
        archiveClassifier.set("") // for publish
        exclude("LICENSE.txt") // mpl
        dependencies { exclude(project(":paper")) }
        relocate("org.mariuszgromada.math", "${rootProject.group}.${rootProject.name}.org.mariuszgromada.math")
    }
    create<ShadowJar>("debugJar") {
        archiveBaseName.set("Tap")
        archiveVersion.set("") // For bukkit plugin update
        archiveClassifier.set("DEBUG")
        from(sourceSets["main"].output)
        configurations = listOf(project.configurations.implementation.get().apply { isCanBeResolved = true })

        var dest = File(rootDir, ".debug/plugins")
        val pluginName = archiveFileName.get()
        val pluginFile = File(dest, pluginName)
        if (pluginFile.exists()) dest = File(dest, "update")

        doLast {
//            dest.mkdirs()
            copy {
                from(archiveFile)
                into(dest)
            }
        }
    }
    create<DefaultTask>("setupWorkspace") {
        doLast {
            val versions = arrayOf(
                "1.17"
            )
            val buildtoolsDir = File(".buildtools")
            val buildtools = File(buildtoolsDir, "BuildTools.jar")

            val maven = File(System.getProperty("user.home"), ".m2/repository/org/spigotmc/spigot/")
            val repos = maven.listFiles { file: File -> file.isDirectory } ?: emptyArray()
            val missingVersions = versions.filter { version ->
                repos.find { it.name.startsWith(version) }?.also { println("Skip downloading spigot-$version") } == null
            }.also { if (it.isEmpty()) return@doLast }

            val download by registering(de.undercouch.gradle.tasks.download.Download::class) {
                src("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar")
                dest(buildtools)
            }
            download.get().download()

            runCatching {
                for (v in missingVersions) {
                    println("Downloading spigot-$v...")

                    javaexec {
                        workingDir(buildtoolsDir)
                        mainClass.set("-jar")
                        args = listOf("./${buildtools.name}", "--rev", v, "--disable-java-check", "--remapped")
                        // Silent
                        standardOutput = nullOutputStream()
                        errorOutput = nullOutputStream()
                    }
                }
            }.onFailure {
                it.printStackTrace()
            }
            buildtoolsDir.deleteRecursively()
        }
    }
    build {
        finalizedBy(shadowJar)
    }
}

publishing {
    publications {
        create<MavenPublication>("Tap") {
            project.shadow.component(this)
            artifact(tasks["sourcesJar"])
        }
    }
}