package pl.touk.nussknacker.engine.testmode

import pl.touk.nussknacker.engine.api.component.{Component, ComponentDefinition}
import pl.touk.nussknacker.engine.api.{MethodToInvoke, ParamName, Service}

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class InvocationsCollectingService extends Service {
  private val invocationResult: mutable.MutableList[Any] = mutable.MutableList()

  @MethodToInvoke
  def invoke(@ParamName("value") value: Any)(implicit ec: ExecutionContext): Future[Unit] = {
    Future.successful {
      invocationResult += value
    }
  }

  def data[T](): List[T] = invocationResult.toArray.toList.map(_.asInstanceOf[T])
}

case class TestComponentHolder(runId: TestRunId) extends Serializable {
  def results[T](runId: TestRunId): List[T] = TestComponentsHolder.results(runId)

  def components[T <: Component : ClassTag]: List[ComponentDefinition] = TestComponentsHolder.componentsForId[T](runId)

  def clean(): Unit = TestComponentsHolder.clean(runId)
}

object TestComponentsHolder {
  def results[T](runId: TestRunId): List[T] = invocationCollectors(runId).data()

  private var invocationCollectors = Map[TestRunId, InvocationsCollectingService]()
  private var components = Map[TestRunId, List[ComponentDefinition]]()

  def componentsForId[T <: Component : ClassTag](id: TestRunId): List[ComponentDefinition] = components(id).collect {
    case ComponentDefinition(name, component: T, _, _) => ComponentDefinition(name, component)
  }

  def registerTestComponents(componentDefinitions: List[ComponentDefinition]): TestComponentHolder = synchronized {
    val runId = TestRunId(UUID.randomUUID().toString)
    val invocationCollector = new InvocationsCollectingService
    val definitions = componentDefinitions :+ ComponentDefinition("invocationCollector", invocationCollector)
    components += (runId -> definitions)
    invocationCollectors += (runId -> invocationCollector)
    TestComponentHolder(runId)
  }

  def clean(runId: TestRunId): Unit = synchronized {
    components -= runId
    invocationCollectors -= runId
  }

}