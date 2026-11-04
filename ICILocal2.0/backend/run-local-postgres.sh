#!/bin/sh
# Disposable development database; never use for persistent school records.
set -eu
cd "$(dirname "$0")"
./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/local-classpath.txt
exec java -cp "target/test-classes:target/classes:$(cat target/local-classpath.txt)" ph.edu.ici.LocalPostgresApplication "$@"
