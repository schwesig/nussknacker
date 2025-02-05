package pl.touk.nussknacker.engine.flink.util.signal

import com.github.ghik.silencer.silent
import org.apache.flink.api.common.serialization.DeserializationSchema
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.{ConnectedStreams, DataStream}
import org.apache.flink.streaming.api.functions.IngestionTimeExtractor
import pl.touk.nussknacker.engine.kafka.{KafkaConfig, KafkaUtils}
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer

trait KafkaSignalStreamConnector {
  val kafkaConfig: KafkaConfig
  val signalsTopic: String

  def connectWithSignals[A, B: TypeInformation](start: DataStream[A], processId: String, nodeId: String, schema: DeserializationSchema[B]): ConnectedStreams[A, B] = {
    @silent("deprecated")
    val signalsSource = new FlinkKafkaConsumer[B](signalsTopic, schema,
      KafkaUtils.toConsumerProperties(kafkaConfig, Some(s"$processId-$nodeId-signal")))
    val signalsStream = start.getExecutionEnvironment
      .addSource(signalsSource).name(s"signals-$processId-$nodeId")
    val withTimestamps = assignTimestampsAndWatermarks(signalsStream)
    start.connect(withTimestamps)
  }

  //We use ingestion time here, to advance watermarks in connected streams
  //This is not always optimal solution, as e.g. in tests periodic watermarks are not the best option, so it can be overridden in implementations
  //Please note that *in general* it's not OK to assign standard event-based watermark, as signal streams usually
  //can be idle for long time. This prevent advancement of watermark on connected stream, which can lean to unexpected behaviour e.g. in aggregates
  @silent("deprecated")
  protected def assignTimestampsAndWatermarks[B](dataStream: DataStream[B]): DataStream[B] = {
    dataStream.assignTimestampsAndWatermarks(new IngestionTimeExtractor[B])
  }

}
