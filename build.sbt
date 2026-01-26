val scala3Version = "3.7.4"
lazy val pekkoVersion = "1.4.0"

lazy val root = project
  .enablePlugins(NativeImagePlugin)
  .in(file("."))
  .settings(
    name := "radisson",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions ++= Seq("-Wunused:imports"),
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") =>
        MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    libraryDependencies ++= Seq(
      // Testing
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,

      // Apache Pekko
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % "1.3.0",

      // HTTP Client (sttp with Pekko backend)
      "com.softwaremill.sttp.client4" %% "core" % "4.0.13",
      "com.softwaremill.sttp.client4" %% "circe" % "4.0.13",
      "com.softwaremill.sttp.client4" %% "pekko-http-backend" % "4.0.13",

      // JSON (circe)
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-generic" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "io.circe" %% "circe-yaml" % "0.16.0",

      // Logging (SLF4J 2.x compatible)
      "ch.qos.logback" % "logback-classic" % "1.5.12"
    ),
    Compile / mainClass := Some("radisson.Main"),
    nativeImageOptions ++= Seq(
      "--no-fallback",
      "-H:IncludeResources=.*\\.conf",
      "-H:IncludeResources=.*\\.xml",
      "-H:IncludeResources=.*\\.properties",
      "--initialize-at-run-time=org.apache.pekko",
      "--initialize-at-run-time=com.typesafe.config",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=ch.qos.logback",
      "-H:+ReportExceptionStackTraces",
      "--verbose"
    ),
    nativeImageJvm := "graalvm-java21",
    nativeImageVersion := "21.0.2"
  )
