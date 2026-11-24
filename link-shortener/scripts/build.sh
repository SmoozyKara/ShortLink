#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/main out/test
find src/main/java -name '*.java' | sort > out/main-sources.txt
find src/test/java -name '*.java' | sort > out/test-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d out/main @out/main-sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp out/main -d out/test @out/test-sources.txt
java -cp out/main:out/test shortener.TestRunner
jar --create --file out/link-shortener.jar --main-class shortener.Main -C out/main .
