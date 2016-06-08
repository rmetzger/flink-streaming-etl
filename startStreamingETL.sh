#!/bin/bash

# clean up rolling sink
rm -r rolling-sink/*

/home/robert/incubator-flink/build-target/bin/flink run -p 1 -c com.dataartisans.StreamingETL \
 /home/robert/flink-workdir/flink-streaming-etl/target/flink-streaming-etl-1.0-SNAPSHOT.jar \
 /home/robert/flink-workdir/flink-streaming-etl/etl.properties
