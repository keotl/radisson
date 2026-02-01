## Update tracing reflection calls
```
# Build jar
sbt assembly

# Add graalvm to java path (if not set)
export JAVA_HOME=<path to graalvm-community-openjdk-21.0.2+13.1>/
export PATH=$JAVA_HOME/bin:$PATH

# Run in graalvm tracing mode
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/radisson -jar target/scala*/radisson-assembly*.jar

# Alternatively, run tracing in MERGING mode
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/radisson -jar target/scala*/radisson-assembly*.jar

# TODO: Run through all executable code paths
```

