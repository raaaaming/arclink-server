plugins {
	kotlin("jvm") version "2.3.21"
	id("com.google.devtools.ksp") version "2.3.7"
	`maven-publish`
}

group = "kr.acda"
version = "1.0.0"

repositories {
	mavenCentral()
	mavenLocal()
	maven("https://repo.papermc.io/repository/maven-public/")
	maven("https://repo.acda.kr/repository/maven-releases/")
	maven("https://repo.acda.kr/repository/maven-snapshots/")
}

dependencies {
	testImplementation(kotlin("test"))
	// Gson only reaches the main sourceSet transitively via paper-api/arc-core (both
	// compileOnly, so not inherited by the test sourceSet) — tests need it directly.
	testImplementation("com.google.code.gson:gson:2.11.0")
	testImplementation("kr.acda.arccore:arc-core:1.1.0-SNAPSHOT")
	compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

	compileOnly("kr.acda.arccore:arc-core:1.1.0-SNAPSHOT")
	ksp("kr.acda.arccore:arc-ksp:1.1.0-SNAPSHOT")

	// Auto-downloaded at module load time via @ModuleSpec(libraries = [...]) — compileOnly here,
	// ARCCore's module loader supplies it at runtime, so it must not be shaded into this jar.
	compileOnly("org.java-websocket:Java-WebSocket:1.6.0")
}

kotlin {
	jvmToolchain(21)
}

java {
	withSourcesJar()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			groupId = project.group.toString()
			artifactId = project.name
			version = project.version.toString()
			from(components["java"])
			pom { name.set(project.name) }
		}
	}
	repositories {
		maven {
			name = "nexus"
			val isSnapshot = project.version.toString().endsWith("SNAPSHOT")
			url = uri(
				if (isSnapshot)
					"https://repo.acda.kr/repository/maven-snapshots/"
				else
					"https://repo.acda.kr/repository/maven-releases/"
			)
			credentials {
				username = findProperty("repoUser")?.toString() ?: System.getenv("REPO_USER")
				password = findProperty("repoPassword")?.toString() ?: System.getenv("REPO_PASSWORD")
			}
		}
	}
}

tasks.test {
	useJUnitPlatform()
}
