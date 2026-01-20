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
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
    libraryDependencies += "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    libraryDependencies += "org.apache.pekko" %% "pekko-http" % "1.3.0",
    libraryDependencies += "com.softwaremill.sttp.client4" %% "core" % "4.0.13",
    libraryDependencies += "com.softwaremill.sttp.client4" %% "circe" % "4.0.13",
    libraryDependencies += "io.circe" %% "circe-generic" % "0.14.10",
    libraryDependencies += "io.circe" %% "circe-generic-extras" % "0.14.5-RC1"
  )
