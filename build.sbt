val scala3Version = "3.7.4"
lazy val pekkoVersion = "1.4.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "radisson",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions ++= Seq("-Wunused:imports"),
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
    )
  )
