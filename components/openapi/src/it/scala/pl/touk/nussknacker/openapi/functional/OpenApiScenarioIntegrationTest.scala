package pl.touk.nussknacker.openapi.functional

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.IOUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.component.ComponentDefinition
import pl.touk.nussknacker.engine.api.typed.TypedMap
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.flink.test.FlinkSpec
import pl.touk.nussknacker.engine.flink.util.test.NuTestScenarioRunner
import pl.touk.nussknacker.engine.modelconfig.DefaultModelConfigLoader
import pl.touk.nussknacker.engine.spel
import pl.touk.nussknacker.engine.util.config.ConfigEnrichments.RichConfig
import pl.touk.nussknacker.engine.util.config.CustomFicusInstances.{toFicusConfig, urlValueReader}
import pl.touk.nussknacker.engine.util.test.RunResult
import pl.touk.nussknacker.openapi.OpenAPIServicesConfig
import pl.touk.nussknacker.openapi.OpenAPIsConfig.openAPIServicesConfigVR
import pl.touk.nussknacker.openapi.enrichers.SwaggerEnricher
import pl.touk.nussknacker.openapi.http.backend.HttpBackendProvider
import pl.touk.nussknacker.openapi.parser.SwaggerParser
import pl.touk.nussknacker.test.{ValidatedValuesDetailedMessage, VeryPatientScalaFutures}
import sttp.client.Response
import sttp.client.testing.SttpBackendStub

import java.net.URL
import java.nio.charset.StandardCharsets
import java.util
import scala.concurrent.{ExecutionContext, Future}

class OpenApiScenarioIntegrationTest extends AnyFlatSpec with BeforeAndAfterAll with Matchers with FlinkSpec with LazyLogging with VeryPatientScalaFutures with ValidatedValuesDetailedMessage {

  import spel.Implicits._

  type FixtureParam = Int

  def rootUrl(port: Int): String = s"http://localhost:$port/customers"

  def withSwagger(test: Int => Any) = new StubService().withCustomerService { port =>
    test(port)
  }

  def withPrimitiveRequestBody(test: Int => Any) = new StubService("/customer-primitive-swagger.yaml").withCustomerService { port =>
    test(port)
  }

  def withPrimitiveReturnType(test: Int => Any) = new StubService("/customer-primitive-return-swagger.yaml").withCustomerService { port =>
    test(port)
  }

  val stubbedBackend: SttpBackendStub[Future, Nothing, Nothing] = SttpBackendStub.asynchronousFuture[Nothing].whenRequestMatchesPartial {
    case _ => Response.ok((s"""{"name": "Robert Wright", "id": 10, "category": "GOLD"}"""))
  }

  val stubbedBackedProvider: HttpBackendProvider = (_: ExecutionContext) => stubbedBackend

  it should "should enrich scenario with data" in withSwagger { port =>
    //given
    val finalConfig = ConfigFactory.load()
      .withValue("components.openAPI.url", fromAnyRef(s"http://localhost:$port/swagger"))
      .withValue("components.openAPI.rootUrl", fromAnyRef(rootUrl(port)))
    val resolvedConfig = new DefaultModelConfigLoader().resolveInputConfigDuringExecution(finalConfig, getClass.getClassLoader).config
    val openAPIsConfig = config.rootAs[OpenAPIServicesConfig]
    val definition = IOUtils.toString(finalConfig.as[URL]("components.openAPI.url"), StandardCharsets.UTF_8)
    val services = SwaggerParser.parse(definition, openAPIsConfig)

    val stubbedGetCustomerOpenApiService: SwaggerEnricher = new SwaggerEnricher(Some(new URL(rootUrl(port))), services.head, Map.empty, stubbedBackedProvider)
    val data = List("10")
    val mockComponents = List(ComponentDefinition("getCustomer", stubbedGetCustomerOpenApiService))
    val testScenarioRunner = NuTestScenarioRunner
      .flinkBased(resolvedConfig, flinkMiniCluster)
      .withExtraComponents(mockComponents)
      .build()

    val scenario =
      ScenarioBuilder
        .streaming("openapi-test")
        .parallelism(1)
        .source("start", "source")
        .enricher("customer", "customer", "getCustomer", ("customer_id", "#input"))
        .processorEnd("end", "invocationCollector", "value" -> "#customer")

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success(TypedMap(Map("name" -> "Robert Wright", "id" -> 10L, "category" -> "GOLD")))
  }


  it should "call enricher with primitive request body" in withPrimitiveRequestBody { port =>
    //given
    val finalConfig = ConfigFactory.load()
      .withValue("components.openAPI.url", fromAnyRef(s"http://localhost:$port/swagger"))
      .withValue("components.openAPI.rootUrl", fromAnyRef(rootUrl(port)))
    val resolvedConfig = new DefaultModelConfigLoader().resolveInputConfigDuringExecution(finalConfig, getClass.getClassLoader).config
    val openAPIsConfig = config.withValue("allowedMethods", fromAnyRef(util.Arrays.asList("POST"))).rootAs[OpenAPIServicesConfig]
    val definition = IOUtils.toString(finalConfig.as[URL]("components.openAPI.url"), StandardCharsets.UTF_8)
    val services = SwaggerParser.parse(definition, openAPIsConfig)

    val stubbedGetCustomerOpenApiService: SwaggerEnricher = new SwaggerEnricher(Some(new URL(rootUrl(port))), services.head, Map.empty, stubbedBackedProvider)
    val data = List("10")
    val mockComponents = List(ComponentDefinition("getCustomer", stubbedGetCustomerOpenApiService))
    val testScenarioRunner = NuTestScenarioRunner
      .flinkBased(resolvedConfig, flinkMiniCluster)
      .withExtraComponents(mockComponents)
      .build()

    val scenario =
      ScenarioBuilder
        .streaming("openapi-test")
        .parallelism(1)
        .source("start", "source")
        .enricher("customer", "customer", "getCustomer", ("body", "#input"))
        .processorEnd("end", "invocationCollector", "value" -> "#customer")

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success(TypedMap(Map("name" -> "Robert Wright", "id" -> 10L, "category" -> "GOLD")))
  }

  it should "call enricher returning string" in withPrimitiveReturnType { port =>
    //given

    val stubbedBackend: SttpBackendStub[Future, Nothing, Nothing] = SttpBackendStub.asynchronousFuture[Nothing].whenRequestMatchesPartial {
      case _ => Response.ok((s""""justAString""""))
    }
    val stubbedBackedProvider: HttpBackendProvider = (_: ExecutionContext) => stubbedBackend
    val finalConfig = ConfigFactory.load()
      .withValue("components.openAPI.url", fromAnyRef(s"http://localhost:$port/swagger"))
      .withValue("components.openAPI.rootUrl", fromAnyRef(rootUrl(port)))
    val resolvedConfig = new DefaultModelConfigLoader().resolveInputConfigDuringExecution(finalConfig, getClass.getClassLoader).config
    val openAPIsConfig = config.withValue("allowedMethods", fromAnyRef(util.Arrays.asList("POST"))).rootAs[OpenAPIServicesConfig]
    val definition = IOUtils.toString(finalConfig.as[URL]("components.openAPI.url"), StandardCharsets.UTF_8)
    val services = SwaggerParser.parse(definition, openAPIsConfig)

    val stubbedGetCustomerOpenApiService: SwaggerEnricher = new SwaggerEnricher(Some(new URL(rootUrl(port))), services.head, Map.empty, stubbedBackedProvider)
    val data = List("10")
    val mockComponents = List(ComponentDefinition("getCustomer", stubbedGetCustomerOpenApiService))
    val testScenarioRunner = NuTestScenarioRunner
      .flinkBased(resolvedConfig, flinkMiniCluster)
      .withExtraComponents(mockComponents)
      .build()

    val scenario =
      ScenarioBuilder
        .streaming("openapi-test")
        .parallelism(1)
        .source("start", "source")
        .enricher("customer", "customer", "getCustomer", ("body", "#input"))
        .processorEnd("end", "invocationCollector", "value" -> "#customer")

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success("justAString")
  }
}
