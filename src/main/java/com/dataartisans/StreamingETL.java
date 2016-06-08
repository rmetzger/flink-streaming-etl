package com.dataartisans;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.FoldFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.runtime.executiongraph.restart.FixedDelayRestartStrategy;
import org.apache.flink.runtime.executiongraph.restart.RestartStrategyFactory;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.elasticsearch2.ElasticsearchSink;
import org.apache.flink.streaming.connectors.elasticsearch2.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch2.RequestIndexer;
import org.apache.flink.streaming.connectors.fs.DateTimeBucketer;
import org.apache.flink.streaming.connectors.fs.RollingSink;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer09;
import org.apache.flink.streaming.util.serialization.JSONDeserializationSchema;
import org.apache.flink.streaming.util.serialization.SerializationSchema;
import org.apache.flink.util.Collector;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.client.Requests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class StreamingETL {
	public static void main(String[] args) throws Exception {
		// parse arguments
		ParameterTool params = ParameterTool.fromPropertiesFile(args[0]);

		// create streaming environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		// enable event time processing
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		// enable fault-tolerance
		env.enableCheckpointing(1000);

		// enable restarts
		env.setRestartStrategy(RestartStrategies.fixedDelayRestart(50, 500L));

		env.setStateBackend(new FsStateBackend("file:///home/robert/flink-workdir/flink-streaming-etl/state-backend"));

		// run each operator separately
		env.disableOperatorChaining();

		// get data from Kafka
		Properties kParams = params.getProperties();
		kParams.setProperty("group.id", UUID.randomUUID().toString());
		DataStream<ObjectNode> inputStream = env.addSource(new FlinkKafkaConsumer09<>(params.getRequired("topic"), new JSONDeserializationSchema(), kParams)).name("Kafka 0.9 Source")
			.assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor<ObjectNode>(Time.minutes(1L)) {
				@Override
				public long extractTimestamp(ObjectNode jsonNodes) {
					return jsonNodes.get("timestamp_ms").asLong();
				}
			}).name("Timestamp extractor");

		// filter out records without lang field
		DataStream<ObjectNode> tweetsWithLang = inputStream.filter(jsonNode -> jsonNode.has("user") && jsonNode.get("user").has("lang")).name("Filter records without 'lang' field");

		// select only lang = "en" tweets
		DataStream<ObjectNode> englishTweets = tweetsWithLang.filter(jsonNode -> jsonNode.get("user").get("lang").asText().equals("en")).name("Select 'lang'=en tweets");

		// write to file system
		RollingSink<ObjectNode> rollingSink = new RollingSink<>(params.get("sinkPath", "/home/robert/flink-workdir/flink-streaming-etl/rolling-sink"));
		rollingSink.setBucketer(new DateTimeBucketer("yyyy-MM-dd-HH-mm")); // do a bucket for each minute
		englishTweets.addSink(rollingSink).name("Rolling FileSystem Sink");

		// build aggregates (count per language) using window (10 seconds tumbling):
		DataStream<Tuple3<Long, String, Long>> languageCounts = tweetsWithLang.keyBy(jsonNode -> jsonNode.get("user").get("lang").asText())
			.timeWindow(Time.seconds(10))
			.apply(new Tuple3<>(0L, "", 0L), new JsonFoldCounter(), new CountEmitter()).name("Count per Langauage (10 seconds tumbling)");

		// write window aggregate to ElasticSearch
		List<InetSocketAddress> transportNodes = ImmutableList.of(new InetSocketAddress(InetAddress.getByName("localhost"), 9300));
		ElasticsearchSink<Tuple3<Long, String, Long>> elasticsearchSink = new ElasticsearchSink<>(params.toMap(), transportNodes, new ESRequest());

		languageCounts.addSink(elasticsearchSink).name("ElasticSearch2 Sink");

		// word-count on the tweet stream
		DataStream<Tuple2<Date, List<Tuple2<String, Long>>>> topWordCount = tweetsWithLang
			// get text from tweets
			.map(tweet -> tweet.get("text").asText()).name("Get text from Tweets")
			// split text into (word, 1) tuples
			.flatMap(new FlatMapFunction<String, Tuple2<String, Long>>() {
				@Override
				public void flatMap(String s, Collector<Tuple2<String, Long>> collector) throws Exception {
					String[] splits = s.split(" ");
					for (String sp : splits) {
						collector.collect(new Tuple2<>(sp, 1L));
					}
				}
			}).name("Tokenize words")
			// group by word
			.keyBy(0)
			// build 1 min windows, compute every 10 seconds --> count word frequency
			.timeWindow(Time.minutes(1L), Time.seconds(10L)).apply(new WordCountingWindow()).name("Count word frequency (1 min, 10 sec sliding window)")
			// build top n every 10 seconds
			.timeWindowAll(Time.seconds(10L)).apply(new TopNWords(10)).name("TopN Window (10s)");

		// write top Ns to Kafka topic
		topWordCount.addSink(new FlinkKafkaProducer09<>(params.getRequired("wc-topic"), new ListSerSchema(), params.getProperties())).name("Write topN to Kafka");

		env.execute("Streaming ETL");

	}

	private static class JsonFoldCounter implements FoldFunction<ObjectNode, Tuple3<Long, String, Long>> {
		@Override
		public Tuple3<Long, String, Long> fold(Tuple3<Long, String, Long> current, ObjectNode o) throws Exception {
			current.f0++;
			return current;
		}
	}

	private static class CountEmitter implements WindowFunction<Tuple3<Long, String, Long>, Tuple3<Long, String, Long>, String, TimeWindow> {
		@Override
		public void apply(String key, TimeWindow timeWindow, Iterable<Tuple3<Long, String, Long>> iterable, Collector<Tuple3<Long, String, Long>> collector) throws Exception {
			long count = iterable.iterator().next().f0;
			collector.collect(Tuple3.of(count, key, timeWindow.getStart()));
		}
	}

	private static class ESRequest implements ElasticsearchSinkFunction<Tuple3<Long, String, Long>> {

		@Override
		public void process(Tuple3<Long, String, Long> result, RuntimeContext runtimeContext, RequestIndexer requestIndexer) {
			requestIndexer.add(createIndexRequest(result));
		}

		private ActionRequest createIndexRequest(Tuple3<Long, String, Long> result) {
			Map<String, Object> json = new HashMap<>();
			json.put("count", result.f0);
			json.put("lang", result.f1);
			json.put("window-start", result.f2);

			return Requests.indexRequest()
					.index("twitter-stats")
					.type("stats")
					.source(json);
		}
	}

	private static class WordCountingWindow implements WindowFunction<Tuple2<String, Long>, Tuple2<String, Long>, Tuple, TimeWindow> {
		@Override
		public void apply(Tuple key, TimeWindow timeWindow, Iterable<Tuple2<String, Long>> iterable, Collector<Tuple2<String, Long>> collector) throws Exception {
			long count = 0;
			for(Tuple2<String, Long> e: iterable) {
				count += e.f1;
			}
			collector.collect(Tuple2.of(((Tuple1<String>)key).f0, count));
		}
	}

	private static class TopNWords implements AllWindowFunction<Tuple2<String, Long>, Tuple2<Date,List<Tuple2<String, Long>>>, TimeWindow> {
		private final int n;

		public TopNWords(int n) {
			this.n = n;
		}

		@Override
		public void apply(TimeWindow timeWindow, Iterable<Tuple2<String, Long>> iterable, Collector<Tuple2<Date, List<Tuple2<String, Long>>>> collector) throws Exception {
			// put words in list
			List<Tuple2<String, Long>> words = new ArrayList<>();
			for(Tuple2<String, Long> word: iterable) {
				words.add(word);
			}

			if(words.size() > 0) {
				// sort list
				Collections.sort(words, (o1, o2) -> -1*Long.compare(o1.f1, o2.f1));
				// return top n
				List<Tuple2<String, Long>> sublist = new ArrayList<>(words.subList(0, Math.min(n, words.size())));
				collector.collect(Tuple2.of(new Date(timeWindow.getStart()), sublist));
			}
		}
	}

	private static class ListSerSchema implements SerializationSchema<Tuple2<Date, List<Tuple2<String, Long>>>> {

		@Override
		public byte[] serialize(Tuple2<Date, List<Tuple2<String, Long>>> tuple2) {
			return (tuple2.f0.toString() + " - " + tuple2.toString()).getBytes();
		}
	}


	/**
	 * Create ES Index:
	 *
	 *
	 curl -XPUT "http://localhost:9200/twitter-stats/_mapping/stats" -d'
	 {
	 "stats" : {
	 "properties" : {
	 "count": {"type": "long"},
	 "lang": {"type": "string"},
	 "window-start": {"type": "date"}
	 },
	 "_timestamp" : {"enabled" : true, "path" : "window-start", "store": "yes" }
	 }
	 }'


	 Sample tweet:
	 {"created_at":"Wed Jun 01 13:35:32 +0000 2016","id":738001034451156992,"id_str":"738001034451156992","text":"@ceyekku mau eek ak","source":"\u003ca href=\"http:\/\/twitter.com\/download\/android\" rel=\"nofollow\"\u003eTwitter for Android\u003c\/a\u003e","truncated":false,"in_reply_to_status_id":737989421799067648,"in_reply_to_status_id_str":"737989421799067648","in_reply_to_user_id":4201628843,"in_reply_to_user_id_str":"4201628843","in_reply_to_screen_name":"ceyekku","user":{"id":4292546423,"id_str":"4292546423","name":"binnie","screen_name":"machiattous","location":"benten ;","url":"http:\/\/twitter.com\/kayirrie","description":"bakpjm","protected":false,"verified":false,"followers_count":152,"friends_count":145,"listed_count":5,"favourites_count":2815,"statuses_count":19744,"created_at":"Fri Nov 27 04:32:27 +0000 2015","utc_offset":-25200,"time_zone":"Pacific Time (US & Canada)","geo_enabled":false,"lang":"en","contributors_enabled":false,"is_translator":false,"profile_background_color":"C0DEED","profile_background_image_url":"http:\/\/abs.twimg.com\/images\/themes\/theme1\/bg.png","profile_background_image_url_https":"https:\/\/abs.twimg.com\/images\/themes\/theme1\/bg.png","profile_background_tile":false,"profile_link_color":"0084B4","profile_sidebar_border_color":"C0DEED","profile_sidebar_fill_color":"DDEEF6","profile_text_color":"333333","profile_use_background_image":true,"profile_image_url":"http:\/\/pbs.twimg.com\/profile_images\/736159560688226304\/Xchm2Pnq_normal.jpg","profile_image_url_https":"https:\/\/pbs.twimg.com\/profile_images\/736159560688226304\/Xchm2Pnq_normal.jpg","profile_banner_url":"https:\/\/pbs.twimg.com\/profile_banners\/4292546423\/1464349616","default_profile":true,"default_profile_image":false,"following":null,"follow_request_sent":null,"notifications":null},"geo":null,"coordinates":null,"place":null,"contributors":null,"is_quote_status":false,"retweet_count":0,"favorite_count":0,"entities":{"hashtags":[],"urls":[],"user_mentions":[{"screen_name":"ceyekku","name":"CEYEK.","id":4201628843,"id_str":"4201628843","indices":[0,8]}],"symbols":[]},"favorited":false,"retweeted":false,"filter_level":"low","lang":"in","timestamp_ms":"1464788132666"}

	 {
	 "created_at":"Wed Jun 01 13:35:32 +0000 2016",
	 "id":738001034451156992,
	 "id_str":"738001034451156992",
	 "text":"@ceyekku mau eek ak",
	 "source":"\u003ca href=\"http:\/\/twitter.com\/download\/android\" rel=\"nofollow\"\u003eTwitter for Android\u003c\/a\u003e",
	 "truncated":false,
	 "in_reply_to_status_id":737989421799067648,
	 "in_reply_to_status_id_str":"737989421799067648",
	 "in_reply_to_user_id":4201628843,
	 "in_reply_to_user_id_str":"4201628843",
	 "in_reply_to_screen_name":"ceyekku",
	 "user":{
	 "id":4292546423,
	 "id_str":"4292546423",
	 "name":"binnie",
	 "screen_name":"machiattous",
	 "location":"benten ;",
	 "url":"http:\/\/twitter.com\/kayirrie",
	 "description":"bakpjm",
	 "protected":false,
	 "verified":false,
	 "followers_count":152,
	 "friends_count":145,
	 "listed_count":5,
	 "favourites_count":2815,
	 "statuses_count":19744,
	 "created_at":"Fri Nov 27 04:32:27 +0000 2015",
	 "utc_offset":-25200,
	 "time_zone":"Pacific Time (US & Canada)",
	 "geo_enabled":false,
	 "lang":"en",
	 "contributors_enabled":false,
	 "is_translator":false,
	 "profile_background_color":"C0DEED",
	 "profile_background_image_url":"http:\/\/abs.twimg.com\/images\/themes\/theme1\/bg.png",
	 "profile_background_image_url_https":"https:\/\/abs.twimg.com\/images\/themes\/theme1\/bg.png",
	 "profile_background_tile":false,
	 "profile_link_color":"0084B4",
	 "profile_sidebar_border_color":"C0DEED",
	 "profile_sidebar_fill_color":"DDEEF6",
	 "profile_text_color":"333333",
	 "profile_use_background_image":true,
	 "profile_image_url":"http:\/\/pbs.twimg.com\/profile_images\/736159560688226304\/Xchm2Pnq_normal.jpg",
	 "profile_image_url_https":"https:\/\/pbs.twimg.com\/profile_images\/736159560688226304\/Xchm2Pnq_normal.jpg",
	 "profile_banner_url":"https:\/\/pbs.twimg.com\/profile_banners\/4292546423\/1464349616",
	 "default_profile":true,
	 "default_profile_image":false,
	 "following":null,
	 "follow_request_sent":null,
	 "notifications":null
	 },
	 "geo":null,
	 "coordinates":null,
	 "place":null,
	 "contributors":null,
	 "is_quote_status":false,
	 "retweet_count":0,
	 "favorite_count":0,
	 "entities":{
	 "hashtags":[

	 ],
	 "urls":[

	 ],
	 "user_mentions":[
	 {
	 "screen_name":"ceyekku",
	 "name":"CEYEK.",
	 "id":4201628843,
	 "id_str":"4201628843",
	 "indices":[
	 0,
	 8
	 ]
	 }
	 ],
	 "symbols":[

	 ]
	 },
	 "favorited":false,
	 "retweeted":false,
	 "filter_level":"low",
	 "lang":"in",
	 "timestamp_ms":"1464788132666"
	 }

	 */
}
