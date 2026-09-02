import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        instrumentationTools()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

/**
 * `CHANGELOG.md`의 맨 위 절을 `Settings > Plugins`에 실을 HTML로 옮긴다.
 *
 * 디스크에서 zip을 설치해 쓰다 보면 **무엇이 설치돼 있는지 겉으로 알 길이 없다.** 버전과 이 목록이
 * 그것을 말해 준다. 그래서 CHANGELOG를 정본으로 두고 여기서 읽는다 — 두 곳에 따로 적으면 곧 갈라진다.
 */
fun latestChangeNotes(): String {
    val file = file("CHANGELOG.md")
    if (!file.exists()) {
        return ""
    }

    val lines = file.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) {
        return ""
    }
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else start + 1 + it }
    val section = lines.subList(start, end)

    fun html(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // 마크다운 코드 표기만 옮긴다. 변경 내역에 쓰는 서식은 사실상 이것뿐이다.
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    val items = section.drop(1).map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }

    return buildString {
        append("<h3>").append(html(section.first().removePrefix("## ").trim())).append("</h3>")
        if (items.isNotEmpty()) {
            append("<ul>")
            items.forEach { append("<li>").append(html(it)).append("</li>") }
            append("</ul>")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        changeNotes = provider { latestChangeNotes() }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    test {
        useJUnit()
    }
}
