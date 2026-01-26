#!/bin/sh
set -e
apt update
apt install -y build-essential zlib1g-dev
curl -fL https://github.com/coursier/coursier/releases/download/v2.1.24/coursier > /usr/local/bin/cs
chmod +x /usr/local/bin/cs
cs install --dir /usr/local/bin scalac:3.7.4 sbt:1.12.0

sbt assembly
sbt nativeImage
