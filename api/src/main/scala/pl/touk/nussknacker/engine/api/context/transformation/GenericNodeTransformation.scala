package pl.touk.nussknacker.engine.api.context.transformation

import cats.data.ValidatedNel
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.NodeId
import pl.touk.nussknacker.engine.api.context.{ProcessCompilationError, ValidationContext}
import pl.touk.nussknacker.engine.api.definition.{NodeDependency, Parameter}
import pl.touk.nussknacker.engine.api.typed.typing.{TypingResult, Unknown}

/*
  This trait provided most generic way of defining Node. In particular, implementations can dynamically define parameter list
  and target validation context, based on current parameters.
  Please @see LastVariableFilterTransformer for sample usage

  NOTE: this is *experimental* API, subject to changes. In particular:
   - handling dependencies probably will change. In particular definition of OutputVariable/ValidationContext transformation
     for sources/sinks is subject to limitations:
     - for sinks OutputVariable is not handled, result ValidationContext will be ignored
     - for sources OutputVariable *has* to be used for Flink sources, it's value is always equal to 'input' ATM, due to source API limitations
 */
trait GenericNodeTransformation[T] {

  //ValidationContext for single input, Map[String, ValidationContext] for joins
  type InputContext

  type DefinedParameter <: BaseDefinedParameter

  //State is arbitrary data that can be passed between steps of NodeTransformationDefinition
  type State

  //TODO: what if we cannot determine parameters/context? With some "fatal validation error"?
  type NodeTransformationDefinition = PartialFunction[TransformationStep, TransformationStepResult]

  def contextTransformation(context: InputContext, dependencies: List[NodeDependencyValue])(implicit nodeId: NodeId): NodeTransformationDefinition

  def implementation(params: Map[String, Any], dependencies: List[NodeDependencyValue], finalState: Option[State]): T

  //Here we assume that this list is fixed - cannot be changed depending on parameter values
  def nodeDependencies: List[NodeDependency]

  // FinalResult which will be used if some TransformationStep won't be handled inside contextTransformation.
  // Especially useful for cases when evaluation of some parameter will fail
  def fallbackFinalResult(step: TransformationStep, inputContext: InputContext, outputVariable: Option[String])(implicit nodeId: NodeId): FinalResults = {
    prepareFinalResultWithOptionalVariable(inputContext, outputVariable.map(name => (name, Unknown)), step.state)
  }

  protected final def prepareFinalResultWithOptionalVariable(inputContext: InputContext, outputVariable: Option[(String, TypingResult)], state: Option[State])(implicit nodeId: NodeId): FinalResults = {
    val context = inputContext match {
      case single: ValidationContext => single
      case _ => ValidationContext.empty
    }
    outputVariable.map {
      case (name, typ) =>
        FinalResults.forValidation(context, state = state)(_.withVariable(name, typ, paramName = None))
    }.getOrElse(FinalResults(context, state = state))
  }

  sealed trait TransformationStepResult {
    def errors: List[ProcessCompilationError]
  }

  case class NextParameters(parameters: List[Parameter],
                            errors: List[ProcessCompilationError] = Nil, state: Option[State] = None) extends TransformationStepResult

  case class FinalResults(finalContext: ValidationContext,
                          errors: List[ProcessCompilationError] = Nil, state: Option[State] = None) extends TransformationStepResult


  object FinalResults {

    def forValidation(context: ValidationContext, errors: List[ProcessCompilationError] = Nil, state: Option[State] = None)
                     (validation: ValidationContext => ValidatedNel[ProcessCompilationError, ValidationContext]): FinalResults = {
      val validatedFinalContext = validation(context)
      FinalResults(
        validatedFinalContext.getOrElse(context),
        errors ++ validatedFinalContext.swap.map(_.toList).getOrElse(Nil),
        state
      )
    }

  }

  case class TransformationStep(parameters: List[(String, DefinedParameter)], state: Option[State])

}


trait SingleInputGenericNodeTransformation[T] extends GenericNodeTransformation[T] {
  type InputContext = ValidationContext
  type DefinedParameter = DefinedSingleParameter
}

/*
  NOTE: currently, due to FE limitations, it's *NOT* possible to defined dynamic branch parameters - that is,
  branch parameters that are changed based on other parameter values
 */
trait JoinGenericNodeTransformation[T] extends GenericNodeTransformation[T] {
  type InputContext = Map[String, ValidationContext]
  type DefinedParameter = BaseDefinedParameter
}
