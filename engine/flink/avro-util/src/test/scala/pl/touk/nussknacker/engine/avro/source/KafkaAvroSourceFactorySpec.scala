package pl.touk.nussknacker.engine.avro.source

import java.nio.charset.StandardCharsets

import com.typesafe.config.ConfigFactory
import io.confluent.kafka.schemaregistry.client.{SchemaRegistryClient => CSchemaRegistryClient}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.scala._
import org.scalatest.Assertion
import pl.touk.nussknacker.engine.Interpreter
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.{CustomNodeError, NodeId}
import pl.touk.nussknacker.engine.api.context.ValidationContext
import pl.touk.nussknacker.engine.api.context.transformation.TypedNodeDependencyValue
import pl.touk.nussknacker.engine.api.process.{Source, TestDataGenerator, TestDataParserProvider}
import pl.touk.nussknacker.engine.api.typed.ReturningType
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedObjectTypingResult, Unknown}
import pl.touk.nussknacker.engine.api.{MetaData, StreamMetaData}
import pl.touk.nussknacker.engine.avro.KafkaAvroFactory.{SchemaVersionParamName, TopicParamName}
import pl.touk.nussknacker.engine.avro.schema.{FullNameV1, FullNameV2}
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.ConfluentSchemaRegistryProvider
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.client._
import pl.touk.nussknacker.engine.avro.schemaregistry.{SchemaSubjectNotFound, SchemaVersionNotFound}
import pl.touk.nussknacker.engine.avro.typed.AvroSchemaTypeDefinitionExtractor
import pl.touk.nussknacker.engine.avro.{KafkaAvroFactory, KafkaAvroSpecMixin}
import pl.touk.nussknacker.engine.compile.ExpressionCompiler
import pl.touk.nussknacker.engine.compile.nodevalidation.{GenericNodeTransformationValidator, TransformationResult}
import pl.touk.nussknacker.engine.expression.ExpressionEvaluator
import pl.touk.nussknacker.engine.graph.evaluatedparam.Parameter
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.spel.Implicits._
import pl.touk.nussknacker.engine.testing.{EmptyProcessConfigCreator, LocalModelData}

class KafkaAvroSourceFactorySpec extends KafkaAvroSpecMixin with KafkaAvroSourceSpecMixin {

  import KafkaAvroSourceMockSchemaRegistry._

  override protected def schemaRegistryClient: CSchemaRegistryClient = schemaRegistryMockClient

  override protected def confluentClientFactory: ConfluentSchemaRegistryClientFactory = factory

  test("should read generated record in v1") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = FullNameV1.createRecord("Jan", "Kowalski")

    roundTripSingleObject(sourceFactory, RecordTopic, 1, givenObj, FullNameV1.schema)
  }

  test("should read generated record in v2") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = FullNameV2.createRecord("Jan", "Maria", "Kowalski")

    roundTripSingleObject(sourceFactory, RecordTopic, 2, givenObj, FullNameV2.schema)
  }

  test("should read generated record in last version") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = FullNameV2.createRecord("Jan", "Maria", "Kowalski")

    roundTripSingleObject(sourceFactory, RecordTopic, null, givenObj, FullNameV2.schema)
  }

  test("should throw exception when schema doesn't exist") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = FullNameV2.createRecord("Jan", "Maria", "Kowalski")

    assertThrowsWithParent[SchemaSubjectNotFound] {
      readLastMessageAndVerify(sourceFactory, "fake-topic", 1, givenObj, FullNameV2.schema)
    }
  }

  test("should throw exception when schema version doesn't exist") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = FullNameV2.createRecord("Jan", "Maria", "Kowalski")

    assertThrowsWithParent[SchemaVersionNotFound] {
      readLastMessageAndVerify(sourceFactory, RecordTopic, 3, givenObj, FullNameV2.schema)
    }
  }

  test("should read last generated simple object") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = false)
    val givenObj = 123123

    roundTripSingleObject(sourceFactory, IntTopic, 1, givenObj, IntSchema)
  }

  test("should read last generated record as a specific class") {
    val sourceFactory = createAvroSourceFactory(useSpecificAvroReader = true)
    val givenObj = FullNameV2("Jan", "Maria", "Nowak")

    roundTripSingleObject(sourceFactory, RecordTopic, 2, givenObj, FullNameV2.schema)
  }

  test("should read last generated key-value object") {
    val sourceFactory = createKeyValueAvroSourceFactory[Int, Int]
    val givenObj = (123, 345)

    val serializedKey = keySerializer.serialize(IntTopic, givenObj._1)
    val serializedValue = valueSerializer.serialize(IntTopic, givenObj._2)
    kafkaClient.sendRawMessage(IntTopic, serializedKey, serializedValue, Some(0))

    readLastMessageAndVerify(sourceFactory, IntTopic, 1, givenObj, IntSchema)
  }

  test("Should validate specific version") {
    val result = validate(TopicParamName -> s"'${KafkaAvroSourceMockSchemaRegistry.RecordTopic}'",
      SchemaVersionParamName -> "1")

    result.errors shouldBe Nil
  }

  test("Should validate latest version") {
    val result = validate(TopicParamName -> s"'${KafkaAvroSourceMockSchemaRegistry.RecordTopic}'",
      SchemaVersionParamName -> "")

    result.errors shouldBe Nil
  }

  test("Should return sane error on invalid topic") {
    val result = validate(TopicParamName -> "'terefere'", SchemaVersionParamName -> "")

    result.errors shouldBe CustomNodeError("id", "Schema subject doesn't exist.", Some(TopicParamName)) ::
      CustomNodeError("id", "Schema subject doesn't exist.", Some(SchemaVersionParamName)) :: Nil
    result.outputContext shouldBe ValidationContext(Map(Interpreter.InputParamName -> Unknown))
  }

  test("Should return sane error on invalid version") {
    val result = validate(TopicParamName -> s"'${KafkaAvroSourceMockSchemaRegistry.RecordTopic}'",
      SchemaVersionParamName -> "12345")

    result.errors shouldBe CustomNodeError("id", "Schema version doesn't exist.", Some(SchemaVersionParamName)) :: Nil
    result.outputContext shouldBe ValidationContext(Map(Interpreter.InputParamName -> Unknown))
  }

  test("Should properly detect input type") {
    val result = validate(TopicParamName -> s"'${KafkaAvroSourceMockSchemaRegistry.RecordTopic}'",
      SchemaVersionParamName -> "")

    result.errors shouldBe Nil
    result.outputContext shouldBe ValidationContext(Map(Interpreter.InputParamName -> TypedObjectTypingResult(
      Map(
        "first" -> Typed[CharSequence],
        "middle" -> Typed[CharSequence],
        "last" -> Typed[CharSequence]
      ), Typed.typedClass[GenericRecord]
    )))
  }

  private def validate(params: (String, Expression)*): TransformationResult = {

    val modelData = LocalModelData(ConfigFactory.empty(), new EmptyProcessConfigCreator)

    val validator = new GenericNodeTransformationValidator(ExpressionCompiler.withoutOptimization(modelData),
      ExpressionEvaluator.unOptimizedEvaluator(modelData))

    implicit val meta: MetaData = MetaData("processId", StreamMetaData())
    implicit val nodeId: NodeId = NodeId("id")
    val paramsList = params.toList.map(p => Parameter(p._1, p._2))
    validator.validateNode(createAvroSourceFactory(false), paramsList, ValidationContext(), Some(Interpreter.InputParamName)).toOption.get
  }

  private def createKeyValueAvroSourceFactory[K: TypeInformation, V: TypeInformation]: KafkaAvroSourceFactory[(K, V)] = {
    val deserializerFactory = new TupleAvroKeyValueKafkaAvroDeserializerSchemaFactory[K, V](factory)
    val provider = ConfluentSchemaRegistryProvider(
      factory,
      None,
      Some(deserializerFactory),
      kafkaConfig,
      useSpecificAvroReader = false,
      formatKey = true
    )
    new KafkaAvroSourceFactory(provider, testProcessObjectDependencies, None)
  }

  private def roundTripSingleObject(sourceFactory: KafkaAvroSourceFactory[_], topic: String, version: Integer, givenObj: Any, expectedSchema: Schema) = {
    pushMessage(givenObj, topic)
    readLastMessageAndVerify(sourceFactory, topic, version, givenObj, expectedSchema)
  }

  private def readLastMessageAndVerify(sourceFactory: KafkaAvroSourceFactory[_], topic: String, version: Integer, givenObj: Any, expectedSchema: Schema): Assertion = {
    val source = createAndVerifySource(sourceFactory, topic, version, expectedSchema)

    val bytes = source.generateTestData(1)
    info("test object: " + new String(bytes, StandardCharsets.UTF_8))
    val deserializedObj = source.testDataParser.parseTestData(bytes)

    deserializedObj shouldEqual List(givenObj)
  }

  private def createAndVerifySource(sourceFactory: KafkaAvroSourceFactory[_], topic: String, version: Integer, expectedSchema: Schema): Source[AnyRef] with TestDataGenerator with TestDataParserProvider[AnyRef] with ReturningType = {
    val source = sourceFactory
      .implementation(Map(KafkaAvroFactory.TopicParamName -> topic, KafkaAvroFactory.SchemaVersionParamName -> version),
        List(TypedNodeDependencyValue(metaData), TypedNodeDependencyValue(nodeId)))
      .asInstanceOf[Source[AnyRef] with TestDataGenerator with TestDataParserProvider[AnyRef] with ReturningType]

    source.returnType shouldEqual AvroSchemaTypeDefinitionExtractor.typeDefinition(expectedSchema)

    source
  }
}