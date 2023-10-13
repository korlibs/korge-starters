import org.apache.tools.ant.taskdefs.condition.Os

val gitSubmodules: List<File> by lazy {
    (rootDir.listFiles()?.toList() ?: emptyList()).filter {
        it.isDirectory && File(it, ".git").isFile
    }
}

fun File.isNewTemplate(): Boolean {
    val file = File(this, "settings.gradle.kts").takeIf { it.exists() } ?: return false
    return "com.soywiz.korge.settings:com.soywiz.korge.settings.gradle.plugin" in file.readText()
}

fun File.execGetStringResult(vararg commandline: String, cmd: Boolean = true): String {
    return ProcessBuilder(*buildList {
        if (cmd && Os.isFamily(Os.FAMILY_WINDOWS)) {
            add("cmd.exe")
            add("/c")
        }
        addAll(commandline)
    }.toTypedArray()).directory(this).start().inputStream.readBytes().toString(Charsets.UTF_8)
}

fun File.exec(vararg commandline: String, fail: Boolean = true, cmd: Boolean = true) {
    println("${commandline.joinToString(" ")}")
    try {
        exec {
            workingDir(this@exec)
            commandLine(*buildList {
                if (cmd && Os.isFamily(Os.FAMILY_WINDOWS)) {
                    add("cmd.exe")
                    add("/c")
                }
                addAll(commandline)
            }.toTypedArray())
        }
    } catch (e: Throwable) {
        if (fail) throw e
    }
}

fun incrementPatchVersion(version: String): String? {
    val range = Regex("(\\d+)").findAll(version).toList().lastOrNull() ?: return null
    return version.replaceRange(range.range, "${range.value.toInt() + 1}")
}

fun File.findLatestGitTag(): String {
    val commitId = execGetStringResult("git", "rev-list", "--tags", "--max-count=1").trim()
    val tag = execGetStringResult("git", "describe", "--tags", commitId).trim()
    return tag
}

fun File.numberOfCommitsSinceTag(tag: String): Int {
    return execGetStringResult("git", "rev-list", "$tag..HEAD", "--count").trim().toInt()
}

fun File.git(vararg args: String, fail: Boolean = true) = exec("git", *args, fail = fail)

tasks {
    val updateRepos by creating {
        doLast {
            for (file in gitSubmodules) {
                println("FILE: $file")

                file.git("pull")
            }
        }
    }
    val updateTemplates by creating {
        doLast {
            val templateSettingsGradleKts = File(rootDir, "_template/settings.gradle.kts.template")
            val template = templateSettingsGradleKts.readText()
            for (file in gitSubmodules) {
                print("FILE: $file...")

                val settingsGradleKts = File(file, "settings.gradle.kts")

                if (File(file, "gradle/libs.versions.toml").takeIf { it.isFile }?.readText()?.contains("korge = { id = \"com.soywiz.korge\", version = \"5.0.") != true) {
                    println("NOT RIGHT TEMPLATE")
                    continue
                }

                if (settingsGradleKts.readText() != template) {
                    settingsGradleKts.writeText(template)
                    file.git("add", "settings.gradle.kts")
                    file.git("commit", "-m", "Updated settings.gradle.kts", fail = false)
                    file.git("push")
                    println("UPDATED")
                } else {
                    println("UP-TO-DATE")
                }
            }
        }
    }
    val makeGradlewExecutable by creating {
        doLast {
            for (file in gitSubmodules) {
                print("FILE: $file...")

                if (File(file, "./gradlew").isFile) {
                    file.git("update-index", "--chmod=+x", "gradlew")
                    file.git("commit", "-m", "Make gradlew executable", fail = false)
                    file.git("push")
                    println("DONE")
                } else {
                    println("DO NOT EXISTS")
                }
            }
        }
    }
    val updateKorgeVersion by creating {
        val newKorgeVersion = "5.0.6"
        val newGradleVersion = "8.3"
        doLast {
            for (file in gitSubmodules) {
                print("FILE: $file...")
                file.git("reset", "--hard")
                file.git("checkout", "main")
                file.git("pull")

                val gradleDistributionFile = File(file, "gradle/wrapper/gradle-wrapper.properties")
                val libsVersionsToml = File(file, "gradle/libs.versions.toml")
                val yarnLockFile = File(file, "kotlin-js-store/yarn.lock")

                if (gradleDistributionFile.isFile) {
                    val gradleOldContent = gradleDistributionFile.readText()
                    val gradleNewContent = gradleOldContent
                        .replace(Regex("^distributionUrl=(.*)$", RegexOption.MULTILINE), "distributionUrl=https\\://services.gradle.org/distributions/gradle-${newGradleVersion}-bin.zip")
                    if (gradleOldContent != gradleNewContent) {
                        gradleDistributionFile.writeText(gradleNewContent)
                        file.git("add", "gradle/wrapper/gradle-wrapper.properties")
                        file.git("commit", "-m", "Upgrade Gradle to $newGradleVersion", fail = false)
                        file.git("push")
                        print("GRADLE UPDATED...")
                    } else {
                        print("GRADLE UP-TO-DATE...")
                    }
                }

                if (!libsVersionsToml.isFile) {
                    println("Can't find $libsVersionsToml")
                    continue
                }
                if (!file.isNewTemplate()) {
                    println("Not using the new template")
                    continue
                }

                val libsVersionsTomlText = libsVersionsToml.readText()
                val regeex = Regex("^korge = \\{ id = \"com.soywiz.korge\", version = \"(.*?)\" \\}", RegexOption.MULTILINE)
                val regeex2 = Regex("^#korge = \\{ id = \"com.soywiz.korge\", version = \"(.*?)\" \\}", RegexOption.MULTILINE)

                val updatedText = libsVersionsTomlText
                    .replace(regeex, "korge = { id = \"com.soywiz.korge\", version = \"$newKorgeVersion\" }")
                    .replace(regeex2, "#korge = { id = \"com.soywiz.korge\", version = \"999.0.0.999\" }")

                if (libsVersionsTomlText != updatedText) {
                    libsVersionsToml.writeText(updatedText)

                    if (yarnLockFile.isFile) {
                        file.exec("./gradlew", "kotlinUpgradeYarnLock")
                    }

                    file.git("add", "gradle/libs.versions.toml")
                    if (yarnLockFile.isFile) file.git("add", "kotlin-js-store/yarn.lock")
                    file.git("commit", "-m", "Upgrade KorGE to $newKorgeVersion", fail = false)
                    file.git("push")
                    println("UPDATED")
                } else {
                    println("ALREADY UPDATED")
                }

            }
        }
    }
    val upgradeYarnLockJs by creating {
        doLast {
            for (file in gitSubmodules) {
                print("FILE: $file...")

                if (File(file, "gradlew").isFile) {
                    try {
                        file.exec("./gradlew", "kotlinUpgradeYarnLock")
                        println("DONE")
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        println("ERROR")
                    }
                } else {
                    println("DO NOT EXISTS")
                }
            }
        }
    }
}
