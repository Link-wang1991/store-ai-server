#!/bin/sh
# Maven Wrapper

JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17.0.11+9/Contents/Home}"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERROR: JAVA_HOME=$JAVA_HOME 不可用"
  exit 1
fi

BASEDIR="$(cd "$(dirname "$0")" && pwd)"
exec "$JAVA_HOME/bin/java" \
  -classpath "$BASEDIR/.mvn/wrapper/maven-wrapper.jar" \
  -Dmaven.multiModuleProjectDirectory="$BASEDIR" \
  org.apache.maven.wrapper.MavenWrapperMain \
  "$@"
