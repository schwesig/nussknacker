package pl.touk.nussknacker.engine.api.typed

import cats.data.{Validated, ValidatedNel}
import cats.implicits.catsSyntaxValidatedId
import io.circe.{ACursor, Decoder, DecodingFailure, Json}
import io.circe.Json.{fromBoolean, fromDouble, fromFloat, fromInt, fromLong, fromString}
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedClass}

// TODO: Add support for more types.
object SimpleObjectEncoder {
  private val intClass = Typed.typedClass[Int]
  private val longClass = Typed.typedClass[Long]
  private val floatClass = Typed.typedClass[Float]
  private val doubleClass = Typed.typedClass[Double]
  private val booleanClass = Typed.typedClass[Boolean]
  private val stringClass = Typed.typedClass[String]

  def encode(typ: TypedClass, data: Any): ValidatedNel[String, Json] = (typ, data) match {
    case (`intClass`, intValue: Int) =>
      fromInt(intValue).validNel
    case (`longClass`, longValue: Long) =>
      fromLong(longValue).validNel
    case (`floatClass`, floatValue: Float) =>
      fromFloat(floatValue).map(_.validNel).getOrElse(s"Could not encode $floatValue as json.".invalidNel)
    case (`doubleClass`, doubleValue: Double) =>
      fromDouble(doubleValue).map(_.validNel).getOrElse(s"Could not encode $doubleValue as json.".invalidNel)
    case (`booleanClass`, booleanValue: Boolean) =>
      fromBoolean(booleanValue).validNel
    case (`stringClass`, stringValue: String) =>
      fromString(stringValue).validNel
    case (klass, value) if value.getClass == klass.klass =>
      s"No encoding logic for $typ.".invalidNel
    case (klass, value) =>
      s"Mismatched class and value: $klass and $value".invalidNel
  }

  def decode(typ: TypedClass, obj: ACursor): Decoder.Result[Any] = typ match {
    case `intClass` => obj.as[Int]
    case `longClass` => obj.as[Long]
    case `floatClass` => obj.as[Float]
    case `doubleClass` => obj.as[Double]
    case `booleanClass` => obj.as[Boolean]
    case `stringClass` => obj.as[String]
    case typ => Left(DecodingFailure(s"No decoding logic for $typ.", List()))
  }
}
