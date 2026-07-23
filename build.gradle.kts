plugins {
    java
}

group = "com.redstone"
// 版本优先取环境变量 PLUGIN_VERSION（CI 在 tag 时注入 tag 名，去掉前缀 v），
// 本地无该变量时回退到默认值。这样 jar 名 (ChestCapacity-<version>.jar) 与发布 tag 保持一致。
version = (System.getenv("PLUGIN_VERSION") ?: "1.1.1").removePrefix("v")

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        // 用本机 JDK 编译，但产出兼容 Java 21 的字节码（Paper 1.21 运行时为 21）。
        options.release.set(21)
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}