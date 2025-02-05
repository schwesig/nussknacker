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
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.modelconfig.DefaultModelConfigLoader
import pl.touk.nussknacker.engine.spel
import pl.touk.nussknacker.engine.util.test.{ClassBasedTestScenarioRunner, RunResult, TestScenarioRunner}
import pl.touk.nussknacker.openapi.enrichers.SwaggerEnricher
import pl.touk.nussknacker.openapi.parser.SwaggerParser
import pl.touk.nussknacker.openapi.{OpenAPIServicesConfig, SingleBodyParameter}
import pl.touk.nussknacker.test.{ValidatedValuesDetailedMessage, VeryPatientScalaFutures}
import sttp.client.testing.SttpBackendStub
import sttp.client.{Response, SttpBackend}

import java.net.URL
import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}

class OpenApiScenarioIntegrationTest extends AnyFlatSpec with BeforeAndAfterAll with Matchers with FlinkSpec with LazyLogging with VeryPatientScalaFutures with ValidatedValuesDetailedMessage {

  import pl.touk.nussknacker.engine.flink.util.test.FlinkTestScenarioRunner._
  import spel.Implicits._

  def rootUrl(port: Int): String = s"http://localhost:$port/customers"

  // This tests don't use StubService implementation for handling requests - only for serving openapi definition. Invocation is handled by provided sttp backend.
  def withSwagger(sttpBackend: SttpBackend[Future, Nothing, Nothing])(test: ClassBasedTestScenarioRunner => Any) = new StubService().withCustomerService { port =>
    test(prepareScenarioRunner(port, sttpBackend))
  }

  def withPrimitiveRequestBody(sttpBackend: SttpBackend[Future, Nothing, Nothing])(test: ClassBasedTestScenarioRunner => Any) = new StubService("/customer-primitive-swagger.yaml").withCustomerService { port =>
    test(prepareScenarioRunner(port, sttpBackend, OpenAPIServicesConfig(allowedMethods = List("POST"))))
  }

  def withPrimitiveReturnType(sttpBackend: SttpBackend[Future, Nothing, Nothing])(test: ClassBasedTestScenarioRunner => Any) = new StubService("/customer-primitive-return-swagger.yaml").withCustomerService { port =>
    test(prepareScenarioRunner(port, sttpBackend, OpenAPIServicesConfig(allowedMethods = List("POST"))))
  }

  val stubbedBackend: SttpBackendStub[Future, Nothing, Nothing] = SttpBackendStub.asynchronousFuture[Nothing].whenRequestMatchesPartial {
    case _ => Response.ok((s"""{"name": "Robert Wright", "id": 10, "category": "GOLD"}"""))
  }

  it should "should enrich scenario with data" in withSwagger(stubbedBackend) { testScenarioRunner =>
    //given
    val data = List("10")
    val scenario = scenarioWithEnricher(("customer_id", "#input"))

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success(TypedMap(Map("name" -> "Robert Wright", "id" -> 10L, "category" -> "GOLD")))
  }


  it should "call enricher with primitive request body" in withPrimitiveRequestBody (stubbedBackend) { testScenarioRunner =>
    //given
    val data = List("10")
    val scenario = scenarioWithEnricher((SingleBodyParameter.name, "#input"))

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success(TypedMap(Map("name" -> "Robert Wright", "id" -> 10L, "category" -> "GOLD")))
  }

  it should "call enricher returning string" in withPrimitiveReturnType(SttpBackendStub.asynchronousFuture[Nothing].whenRequestMatchesPartial {
    case _ => Response.ok((s""""justAString""""))
  }) { testScenarioRunner =>
    //given
    val data = List("10")
    val scenario = scenarioWithEnricher((SingleBodyParameter.name, "#input"))

    //when
    val result = testScenarioRunner.runWithData(scenario, data)

    //then
    result.validValue shouldBe RunResult.success("justAString")
  }

  private def scenarioWithEnricher(params: (String, Expression)*) = {
    ScenarioBuilder
      .streaming("openapi-test")
      .parallelism(1)
      .source("start", TestScenarioRunner.testDataSource)
      .enricher("customer", "customer", "getCustomer", params: _*)
      .processorEnd("end", TestScenarioRunner.testResultService, "value" -> "#customer")
  }

  private def prepareScenarioRunner(port: Int, sttpBackend: SttpBackend[Future, Nothing, Nothing],
                                    openAPIsConfig: OpenAPIServicesConfig = OpenAPIServicesConfig()) = {
    val url = new URL(s"http://localhost:$port/swagger")
    val finalConfig = ConfigFactory.load()
      .withValue("components.openAPI.url", fromAnyRef(url.toString))
      .withValue("components.openAPI.rootUrl", fromAnyRef(rootUrl(port)))
    val resolvedConfig = new DefaultModelConfigLoader().resolveInputConfigDuringExecution(finalConfig, getClass.getClassLoader).config
    val stubComponent = prepareStubbedComponent(sttpBackend, openAPIsConfig, url)
    // TODO: switch to liteBased after adding ability to override components there (currently there is only option to append not conflicting once) and rename class to *FunctionalTest
    TestScenarioRunner
      .flinkBased(resolvedConfig, flinkMiniCluster)
      .withExtraComponents(List(stubComponent))
      .build()
  }

  private def prepareStubbedComponent(sttpBackend: SttpBackend[Future, Nothing, Nothing], openAPIsConfig: OpenAPIServicesConfig, url: URL) = {
    val definition = IOUtils.toString(url, StandardCharsets.UTF_8)
    val services = SwaggerParser.parse(definition, openAPIsConfig)
    val stubbedGetCustomerOpenApiService = new SwaggerEnricher(url, services.head, Map.empty, (_: ExecutionContext) => sttpBackend)
    ComponentDefinition("getCustomer", stubbedGetCustomerOpenApiService)
  }

}
