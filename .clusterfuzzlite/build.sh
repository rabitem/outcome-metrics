#!/usr/bin/env bash
set -euo pipefail

# Build the core library jar (skip tests; fuzzers exercise the API directly).
./mvnw -B -ntp -pl outcome-metrics -am package -DskipTests

CURRENT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
cp "outcome-metrics/target/outcome-metrics-${CURRENT_VERSION}.jar" "$OUT/outcome-metrics.jar"

PROJECT_JARS="outcome-metrics.jar"
BUILD_CLASSPATH=$(echo "$PROJECT_JARS" | xargs printf -- "$OUT/%s:"):$JAZZER_API_PATH
RUNTIME_CLASSPATH=$(echo "$PROJECT_JARS" | xargs printf -- "\$this_dir/%s:"):\$this_dir

for fuzzer in $(find "$SRC/fuzz" -name '*Fuzzer.java'); do
  fuzzer_basename=$(basename -s .java "$fuzzer")
  javac -cp "$BUILD_CLASSPATH" "$fuzzer"
  cp "$SRC/fuzz/${fuzzer_basename}.class" "$OUT/"

  cat > "$OUT/$fuzzer_basename" <<EOF
#!/bin/sh
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
--cp=$RUNTIME_CLASSPATH \
--target_class=$fuzzer_basename \
--jvm_args="-Xmx2048m:-Djava.awt.headless=true" \
\$@
EOF
  chmod +x "$OUT/$fuzzer_basename"
done
