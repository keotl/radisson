fix-lint:
	scalafmt src/
	sbt scalafix
