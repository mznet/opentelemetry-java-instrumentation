plugins {
  id("otel.javaagent-instrumentation")
}

muzzle {
  pass {
    group.set("com.clickhouse")
    module.set("client-v2")
    versions.set("[0.6.4,)")
    assertInverse.set(true)
  }
}

tasks.withType<Test>().configureEach {
  usesService(gradle.sharedServices.registrations["testcontainersBuildService"].service)
  systemProperty("collectMetadata", findProperty("collectMetadata")?.toString() ?: "false")
}

dependencies {
  implementation(project(":instrumentation:clickhouse:clickhouse-client-common:javaagent"))
  compileOnly("com.clickhouse:client-v2:0.6.4")

  testImplementation(project(":instrumentation:clickhouse:testing"))
  testLibrary("com.clickhouse:client-v2:0.6.4")
}

testing {
  suites {
    val testV07 by registering(JvmTestSuite::class) {
      sources {
        java {
          setSrcDirs(listOf("src/test/java"))
        }
      }
      dependencies {
        implementation(project())
        implementation(project(":instrumentation:clickhouse:testing"))
        implementation("com.clickhouse:client-v2:0.7.1")
        implementation("org.testcontainers:testcontainers")
      }
      targets {
        all {
          testTask.configure {
            usesService(gradle.sharedServices.registrations["testcontainersBuildService"].service)
            filter {
              includeTestsMatching("*ClickHouseClientV2TestV07*")
              excludeTestsMatching("*ClickHouseClientV2TestV06*")
            }
          }
        }
      }
    }
    val testV07StableSemconv by registering(JvmTestSuite::class) {
      sources {
        java {
          setSrcDirs(listOf("src/test/java"))
        }
      }
      dependencies {
        implementation(project())
        implementation(project(":instrumentation:clickhouse:testing"))
        implementation("com.clickhouse:client-v2:0.7.1")
        implementation("org.testcontainers:testcontainers")
      }
      targets {
        all {
          testTask.configure {
            usesService(gradle.sharedServices.registrations["testcontainersBuildService"].service)
            filter {
              includeTestsMatching("*ClickHouseClientV2TestV07*")
              excludeTestsMatching("*ClickHouseClientV2TestV06*")
            }
            jvmArgs("-Dotel.semconv-stability.opt-in=database")
            systemProperty("metadataConfig", "otel.semconv-stability.opt-in=database")
          }
        }
      }
    }
  }
}

tasks {
  named<Test>("test") {
    exclude("**/ClickHouseClientV2TestV07.class")
  }

  val testStableSemconv by registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    jvmArgs("-Dotel.semconv-stability.opt-in=database")
    systemProperty("metadataConfig", "otel.semconv-stability.opt-in=database")
    exclude("**/ClickHouseClientV2TestV07.class")
  }

  check {
    dependsOn(testing.suites, testStableSemconv)
  }
}
