package pl.touk.nussknacker.engine.lite.components.requestresponse

import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import io.circe.syntax.EncoderOps
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.lite.util.test.RequestResponseTestScenarioRunner
import pl.touk.nussknacker.engine.lite.util.test.RequestResponseTestScenarioRunner._
import pl.touk.nussknacker.engine.util.test.TestScenarioRunner
import pl.touk.nussknacker.engine.spel.Implicits._
import pl.touk.nussknacker.test.EitherValuesDetailedMessage

class LiteRequestResponseFunctionalTest extends AnyFunSuite with Matchers with EitherValuesDetailedMessage {

  private val scenarioRunner: RequestResponseTestScenarioRunner = TestScenarioRunner
    .requestResponseBased()
    .build()

  val sampleSchemas: Map[String, String] = Map(
    "inputSchema" -> """{"type":"object","properties": {"field1": {"type": "integer"}}}""",
    "outputSchema" -> """{"type":"object","properties": {"field1": {"type": "integer"}}, "additionalProperties": false}"""
  )

  test("should not compile if passed additional field when schema restricts this") {
    val runner = scenarioRunner
    def scenario(outputValueExpression: String) = ScenarioBuilder
      .requestResponse("test")
      .additionalFields(properties = sampleSchemas)
      .source("input", "request")
      .emptySink("output", "response", "Raw editor" -> "true", "Value" -> outputValueExpression)

    val thrown = the[AssertionError] thrownBy runner.runWithRequests(scenario("""{field1: #input.field1, invalid: 1}""")) { _ =>
    }
    thrown.getMessage shouldBe "Failed to compile: NonEmptyList(CustomNodeError(output,Provided value does not match scenario output JSON schema - errors:\n[#] The object has redundant fields: Set(invalid),Some(Value)))"

    runner.runWithRequests(scenario("""{field1: #input.field1}""")) { invoker =>
      val inputField = 123
      val response = invoker(HttpRequest(HttpMethods.POST, entity = Map("field1" -> inputField).asJson.spaces2)).rightValue
      response.hcursor.downField("field1").as[Int].rightValue shouldEqual inputField
    }
  }

}
