package pl.touk.nussknacker.engine.lite.components.utils

import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.json.JSONObject

object JsonTestData {

  val integerRangeSchema: Schema = SchemaLoader.load(new JSONObject(
    s"""{
       |  "$$schema": "https://json-schema.org/draft-07/schema",
       |  "type": "integer",
       |  "minimum": ${Integer.MIN_VALUE},
       |  "maximum": ${Integer.MAX_VALUE}
       |}""".stripMargin))

  val longSchema: Schema = SchemaLoader.load(new JSONObject(
    """{
      |  "$schema": "https://json-schema.org/draft-07/schema",
      |  "type": "integer"
      |}""".stripMargin))

  val objectSchema: Schema = SchemaLoader.load(new JSONObject(
    """{
      |  "type": "object",
      |  "properties": {
      |    "outgoing" :  {
      |      "type": "object",
      |      "properties": {
      |        "first" : { "type": "string" },
      |        "last" : { "type": "string" }
      |      },
      |      "required": ["first", "last"]
      |    }
      |  }
      |}
      |""".stripMargin))

  val personSchema: Schema = SchemaLoader.load(new JSONObject(
    """{
      |  "$schema": "https://json-schema.org/draft-07/schema",
      |  "type": "object",
      |  "properties": {
      |    "first": {
      |      "type": "string"
      |    },
      |    "last": {
      |      "type": "string"
      |    },
      |    "age": {
      |      "type": "integer"
      |    }
      |  }
      |}""".stripMargin))
}
