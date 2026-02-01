fix-lint:
	scalafmt src/
	sbt scalafix
jar:
	sbt assembly

generate-graalvm-reflection-config: jar
	@echo "Generating GraalVM reflection configuration..."
	@mkdir -p src/main/resources/META-INF/native-image/radisson
	@echo "Starting server with native-image-agent..."
	@java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/radisson \
		-jar target/scala-*/radisson-assembly-*.jar \
		--config=e2e_tests/config/test-config.yaml > /tmp/radisson-agent.log 2>&1 & \
		echo $$! > /tmp/radisson-agent.pid
	@echo "Waiting for server to start..."
	@sleep 5
	@echo "Running e2e tests..."
	@$(MAKE) e2e-tests || (kill $$(cat /tmp/radisson-agent.pid) && rm /tmp/radisson-agent.pid && exit 1)
	@echo "Stopping server..."
	@kill $$(cat /tmp/radisson-agent.pid) && rm /tmp/radisson-agent.pid
	@echo "Reflection configuration generated in src/main/resources/META-INF/native-image/radisson"

e2e-tests:
	@cd e2e_tests && ./run_tests.sh
