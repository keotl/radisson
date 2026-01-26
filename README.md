# Radisson

A proxy server for OpenAI-compatible chat completion endpoints, built
using Apache Pekko. Handles proxying to remote providers and managing
local llama-cpp instances.

## Usage
```
radisson --config=config.yaml
```

See [config.example.yaml](./config.example.yaml) for available
configuration options.

## Development
### Requirements

- Scala 3.7
- sbt 1.12
- (Optional) llama-cpp for local backends

### Building

```bash
# Compile the project
sbt compile

# Run tests
sbt test

# Build a fat JAR
sbt assembly

# Build a GraalVM native image executable
sbt nativeImage

# Running in dev mode
sbt run -- --config=config.yaml

# fix lint
make fix-lint
```

