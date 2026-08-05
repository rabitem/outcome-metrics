# ClusterFuzzLite JVM builder for outcome-metrics (Jazzer).
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cc3c3da52c2f48d7a6caf1d5736ab8ec8cb213f4345329b33cb6e497780b1ed0

RUN apt-get update && apt-get install -y maven

COPY . $SRC/outcome-metrics
WORKDIR $SRC/outcome-metrics
COPY .clusterfuzzlite/build.sh $SRC/
