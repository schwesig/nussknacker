package pl.touk.nussknacker.engine.process.api

import org.apache.flink.api.common.state.ValueStateDescriptor
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.util.Collector
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.flink.api.state.EvictableStateFunction
import pl.touk.nussknacker.engine.flink.test.FlinkTestConfiguration
import pl.touk.nussknacker.engine.flink.util.source.StaticSource
import pl.touk.nussknacker.engine.flink.util.source.StaticSource.{Data, Watermark}
import pl.touk.nussknacker.engine.util.ThreadUtils
import pl.touk.nussknacker.test.VeryPatientScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class EvictableStateTest extends AnyFlatSpec with Matchers with BeforeAndAfter with VeryPatientScalaFutures {

  var futureResult: Future[_] = _

  before {
    StaticSource.running = true

    val env = StreamExecutionEnvironment.createLocalEnvironment(1, FlinkTestConfiguration.configuration())
    env.enableCheckpointing(500)

    env.addSource(StaticSource)
      .keyBy((_: String) => "staticKey")
      .process(new TestOperator)
      .addSink(new SinkFunction[String]{
        override def invoke(value: String, context: SinkFunction.Context): Unit = ()
      })

    futureResult = Future {
      //We need to set context loader to avoid forking in sbt
      ThreadUtils.withThisAsContextClassLoader(getClass.getClassLoader) {
        env.execute()
      }
    }
  }

  after {
    StaticSource.running = false
    TestOperator.buffer = List()
    Await.result(futureResult, 5 seconds)
  }

  it should "process state normally when no watermark is generated" in {

    StaticSource.add(Data(1000, "1"))
    StaticSource.add(Data(2000, "2"))
    StaticSource.add(Data(20000, "3"))

    eventually {
      TestOperator.buffer shouldBe List(List("1"), List("1", "2"), List("1", "2", "3"))
    }

  }

  it should "clear state when watermark recevied" in {

    StaticSource.add(Data(1000, "1"))
    StaticSource.add(Watermark(3000))
    StaticSource.add(Data(2000, "2"))
    StaticSource.add(Watermark(8000))
    StaticSource.add(Data(20000, "3"))

    eventually {
      TestOperator.buffer shouldBe List(List("1"), List("1", "2"), List("3"))
    }

  }


}


class TestOperator extends EvictableStateFunction[String, String, List[String]]  {

  override protected def stateDescriptor: ValueStateDescriptor[List[String]] = new ValueStateDescriptor("st1", classOf[List[String]])

  override def processElement(value: String, ctx: KeyedProcessFunction[String, String, String]#Context, out: Collector[String]): Unit = {
    moveEvictionTime(5000, ctx)

    val newState = Option(state.value()).getOrElse(List()) :+ value

    TestOperator.buffer = TestOperator.buffer :+ newState
    state.update(newState)

    out.collect(value)
  }

}

object TestOperator{

  @volatile var buffer = List[List[String]]()

}
