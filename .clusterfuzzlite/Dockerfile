# ClusterFuzzLite JVM builder for outcome-metrics (Jazzer).
FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && apt-get install -y maven

COPY . $SRC/outcome-metrics
WORKDIR $SRC/outcome-metrics
COPY .clusterfuzzlite/build.sh $SRC/
