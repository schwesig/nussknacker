package pl.touk.nussknacker.engine.avro

import com.typesafe.config.ConfigValueFactory.fromAnyRef
import com.typesafe.config.{Config, ConfigFactory}
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import org.apache.avro.Schema
import org.apache.flink.api.common.typeinfo.TypeInformation
import pl.touk.nussknacker.engine.api.definition.{FixedExpressionValue, FixedValuesParameterEditor}
import pl.touk.nussknacker.engine.api.namespaces.{KafkaUsageKey, NamingContext, ObjectNaming, ObjectNamingParameters}
import pl.touk.nussknacker.engine.api.process.ProcessObjectDependencies
import pl.touk.nussknacker.engine.avro.schema.PaymentV1
import pl.touk.nussknacker.engine.avro.schemaregistry.SchemaRegistryProvider
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.ConfluentSchemaRegistryProvider
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.client.{CachedConfluentSchemaRegistryClientFactory, ConfluentSchemaRegistryClientFactory, MockConfluentSchemaRegistryClientBuilder, MockSchemaRegistryClient}
import pl.touk.nussknacker.engine.kafka.KafkaConfig
import pl.touk.nussknacker.engine.process.FlinkStreamingProcessRegistrar
import pl.touk.nussknacker.engine.process.compiler.FlinkProcessCompiler
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.engine.util.cache.DefaultCache

class NamespacedKafkaSourceSinkTest extends KafkaAvroSpecMixin {

  import KafkaAvroNamespacedMockSchemaRegistry._

  protected val objectNaming: ObjectNaming = new TestObjectNaming(namespace)

  override lazy val config: Config = ConfigFactory.load()
    .withValue("kafka.kafkaAddress", fromAnyRef(kafkaZookeeperServer.kafkaAddress))
    .withValue("kafka.kafkaProperties.\"schema.registry.url\"", fromAnyRef("not_used"))
    .withValue("namespace", fromAnyRef(namespace))

  override protected lazy val testProcessObjectDependencies: ProcessObjectDependencies = ProcessObjectDependencies(config, objectNaming)

  override def schemaRegistryClient: MockSchemaRegistryClient = schemaRegistryMockClient

  override protected def confluentClientFactory: ConfluentSchemaRegistryClientFactory = factory

  private lazy val creator: KafkaAvroTestProcessConfigCreator = new KafkaAvroTestProcessConfigCreator {
    override protected def createSchemaProvider[T: TypeInformation](processObjectDependencies: ProcessObjectDependencies): SchemaRegistryProvider[T] =
      ConfluentSchemaRegistryProvider[T](factory, processObjectDependencies)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    stoppableEnv.start()
    registrar = FlinkStreamingProcessRegistrar(
      new FlinkProcessCompiler(LocalModelData(config, creator, objectNaming = objectNaming)), config
    )
  }

  override protected def afterAll(): Unit = {
    stoppableEnv.stop()
    super.afterAll()
  }

  test("should create source with proper filtered and converted topics") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val editor = Some(FixedValuesParameterEditor(List(
      FixedExpressionValue(s"'input_payment'", "input_payment"),
      FixedExpressionValue(s"'output_payment'", "output_payment")
    )))

    sourceFactory.initialParameters.find(_.name == "topic").head.editor shouldBe editor
  }

  test("should create sink with proper filtered and converted topics") {
    val sinkFactory = createAvroSinkFactory(useSpecificAvroReader = false)
    val editor = Some(FixedValuesParameterEditor(List(
      FixedExpressionValue(s"'input_payment'", "input_payment"),
      FixedExpressionValue(s"'output_payment'", "output_payment")
    )))

    sinkFactory.initialParameters.find(_.name == "topic").head.editor shouldBe editor
  }

  test("should read event in the same version as source requires and save it in the same version") {
    val topicConfig = TopicConfig(InputPaymentWithNamespaced, OutputPaymentWithNamespaced, PaymentV1.schema, isKey = false)
    // Process should be created from topic without namespace..
    val processTopicConfig = TopicConfig("input_payment", "output_payment", PaymentV1.schema, isKey = false)
    val sourceParam = SourceAvroParam(processTopicConfig, Some(1))
    val sinkParam = SinkAvroParam(processTopicConfig, Some(1), "#input")
    val process = createAvroProcess(sourceParam, sinkParam)

    runAndVerifyResult(process, topicConfig, PaymentV1.record, PaymentV1.record)
  }
}

class TestObjectNaming(namespace: String) extends ObjectNaming {

  private final val NamespacePattern = s"${namespace}_(.*)".r

  override def prepareName(originalName: String, config: Config, namingContext: NamingContext): String = namingContext.usageKey match {
    case KafkaUsageKey => s"${namespace}_$originalName"
    case _ => originalName
  }

  override def decodeName(preparedName: String, config: Config, namingContext: NamingContext): Option[String] =
    (namingContext.usageKey, preparedName) match {
      case (KafkaUsageKey, NamespacePattern(value)) => Some(value)
      case _ => Option.empty
    }

  override def objectNamingParameters(originalName: String, config: Config, namingContext: NamingContext): Option[ObjectNamingParameters] = None
}

object KafkaAvroNamespacedMockSchemaRegistry {

  final val namespace: String = "touk"

  final val TestTopic: String = "test_topic"
  final val SomeTopic: String = "topic"
  final val InputPaymentWithNamespaced: String = s"${namespace}_input_payment"
  final val OutputPaymentWithNamespaced: String = s"${namespace}_output_payment"

  private val IntSchema: Schema = AvroUtils.parseSchema(
    """{
      |  "type": "int"
      |}
    """.stripMargin
  )

  val schemaRegistryMockClient: MockSchemaRegistryClient =
    new MockConfluentSchemaRegistryClientBuilder()
      .register(TestTopic, IntSchema, 1, isKey = true) // key subject should be ignored
      .register(TestTopic, PaymentV1.schema, 1, isKey = false) // topic with bad namespace should be ignored
      .register(SomeTopic, PaymentV1.schema, 1, isKey = false) // topic without namespace should be ignored
      .register(InputPaymentWithNamespaced, PaymentV1.schema, 1, isKey = false)
      .register(OutputPaymentWithNamespaced, PaymentV1.schema, 1, isKey = false)
      .build

  /**
    * It has to be done in this way, because schemaRegistryMockClient is not serializable..
    * And when we use TestSchemaRegistryClientFactory then flink has problem with serialization this..
    */
  val factory: CachedConfluentSchemaRegistryClientFactory =
    new CachedConfluentSchemaRegistryClientFactory(DefaultCache.defaultMaximumSize, None, None, None) {
      override protected def confluentClient(kafkaConfig: KafkaConfig): SchemaRegistryClient =
        schemaRegistryMockClient
    }
}