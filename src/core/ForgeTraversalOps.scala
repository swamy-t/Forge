package ppl.dsl.forge
package core

import java.io.{PrintWriter}
import scala.reflect.SourceContext
import scala.virtualization.lms.common._
import scala.virtualization.lms.internal._
import scala.collection.mutable.{ArrayBuffer,HashMap}

trait ForgeTraversalOps extends Base {
  this: Forge =>

  // --- API

  def transformer(name: String, isExtern: Boolean = false) = forge_transformer(name,isExtern)
  def analyzer(name: String, isExtern: Boolean = false) = forge_analyzer(name,isExtern)
  def schedule(traversal: Rep[DSLTraversal]) = forge_schedule(traversal)

  def rewrite(op: Rep[DSLOp]*) = forge_rewrite(op)
  def lower(xf: Rep[DSLTransformer])(op: Rep[DSLOp]) = forge_lower(xf, op)

  object pattern {
    def apply(rule: (List[String], String)) = forge_pattern(rule._1, unit(rule._2), false)
  }
  object commutative {
    def apply(rule: (List[String], String)) = forge_pattern(rule._1, unit(rule._2), true)
  }
  object rule {
    def apply(rule: Rep[String]) = forge_rule(rule)
  }
  object forwarding {
    def apply(rule: Rep[String]) = forge_forwarding(rule)
  }

  // TODO: Should users be allowed to specify type parameters for metadata?
  // TODO: Should metadata even have type parameters?
  def metadata(name: String): Rep[DSLMetadata] = forge_metadata(name, Nil)
  // Alternate shorter form with fields inlined
  def metadata(name: String, field1: (String, Rep[DSLType]), fields: (String, Rep[DSLType])*): Rep[DSLMetadata] = {
    val m = metadata(name)
    data(m, (field1 +: fields):_*)
    m
  }
  def lookupMeta(name: String) = forge_lookup_metadata(name)

  def meet(meta: Rep[DSLMetadata], alias: MetaMeet = any)(rule: Rep[String]) = forge_meet(meta,alias,rule)



  // --- Forge stubs
  def forge_transformer(name: String, isExtern: Boolean): Rep[DSLTransformer]
  def forge_analyzer(name: String, isExtern: Boolean): Rep[DSLAnalyzer]
  def forge_schedule(traversal: Rep[DSLTraversal]): Rep[Unit]
  def forge_metadata(name: String, tpePars: List[Rep[TypePar]]): Rep[DSLMetadata]
  def forge_lookup_metadata(name: String): Rep[DSLMetadata]

  def forge_rewrite(op: Seq[Rep[DSLOp]]): Rep[DSLPattern]
  def forge_lower(t: Rep[DSLTransformer], op: Rep[DSLOp]): Rep[DSLPattern]
  def forge_rule(rule: Rep[String]): DSLRule
  def forge_forwarding(rule: Rep[String]): DSLRule
  def forge_pattern(pattern: List[String], rule: Rep[String], commutative: Boolean): DSLRule

  def forge_using(pattern: Rep[DSLPattern], rule: DSLRule)(implicit ctx: SourceContext): Rep[Unit]

  def forge_meet(grp: Rep[DSLGroup], func: MetaMeet, rule: Rep[String]): Rep[Unit]
}

trait ForgeTraversalSugarLowPriority extends ForgeTraversalOps {
  this: Forge =>

  implicit def singleStrToList(t: (String,String)): (List[String],String) = (List(t._1),t._2)
  implicit def tuple2StrToList(t: ((String,String),String)): (List[String],String) = (t._1,t._2) match {
    case (a,b) => (List(a._1,a._2), b)
  }
  implicit def tuple3StrToList(t: ((String,String,String),String)): (List[String],String) = (t._1,t._2) match {
    case (a,b) => (List(a._1,a._2,a._3), b)
  }
  implicit def tuple4StrToList(t: ((String,String,String,String),String)): (List[String],String) = (t._1,t._2) match {
    case (a,b) => (List(a._1,a._2,a._3,a._4), b)
  }
  implicit def tuple5StrToList(t: ((String,String,String,String,String),String)): (List[String],String) = (t._1,t._2) match {
    case (a,b) => (List(a._1,a._2,a._3,a._4,a._5), b)
  }
}

trait ForgeTraversalSugar extends ForgeTraversalSugarLowPriority with ForgeTraversalOps {
  this: Forge =>

  def infix_using(pattern: Rep[DSLPattern], rule: DSLRule)(implicit ctx: SourceContext) = forge_using(pattern, rule)

  /**
   * Transformer and analysis sugar scopes
   * Mimics type scopes (Scala-Virtualized scopes for sugar)
   */
  var _xFormScope: Option[Rep[DSLTransformer]] = None
  var _analysisScope: Option[Rep[DSLAnalyzer]] = None
  var _metadataScope: Option[Rep[DSLMetadata]] = None

  implicit class TransformOpsCls(xf: Rep[DSLTransformer]) {
    def apply[R](block: => R) {
      if (_xFormScope.isDefined) forge_err("Cannot create nested transformer scopes!")
      _xFormScope = Some(xf)   // set transformer scope
      new Scope[TransformScope, TransformScopeRunner[R], R](block)
      _xFormScope = None // reset transformer scope
    }
  }
  implicit class AnalysisOpsCls(az: Rep[DSLAnalyzer]) {
    def apply[R](block: => R) {
      if (_analysisScope.isDefined) forge_err("Cannot create nested analysis scopes!")
      _analysisScope = Some(az)
      new Scope[AnalysisScope, AnalysisScopeRunner[R], R](block)
      _analysisScope = None
    }
  }

  // --- Transformer scope sugar
  // TODO: Add other transformer methods here
  trait TransformScope {
    def lower(op: Rep[DSLOp]) = forge_lower(_xFormScope.get, op)
  }
  trait TransformScopeRunner[R] extends TransformScope {
    def apply: R
    val result = apply
  }

  // --- Analysis scope sugar
  trait AnalysisScope {
    //def infix_propagates(op: Rep[DSLOp], rule: Rep[String]) = forge_propagates(_analysisScope.get, op, rule)
    //def infix_updates(op: Rep[DSLOp], idx: Int, rule: Rep[String]) = forge_updates(_analysisScope.get, op, idx, rule)
  }
  trait AnalysisScopeRunner[R] extends AnalysisScope {
    def apply: R
    val result = apply
  }
}

trait ForgeTraversalOpsExp extends ForgeTraversalSugar with BaseExp {
  this: ForgeExp =>

  /**
   * Compiler state
   */
  val Traversals = ArrayBuffer[Exp[DSLTraversal]]()
  val Transformers = HashMap[Exp[DSLTransformer], TraversalRules]()
  val Analyzers = HashMap[Exp[DSLAnalyzer], AnalysisRules]()
  val TraversalSchedule = ArrayBuffer[Exp[DSLTraversal]]()
  val MetaImpls = HashMap[Exp[DSLMetadata], MetaOps]()

  val Rewrites = HashMap[Exp[DSLOp], List[DSLRule]]()

  /**
   * Traversal op patterns
   */
  case class RewritePattern(op: Rep[DSLOp]) extends Def[DSLPattern]
  case class RewriteSetPattern(op: List[Rep[DSLOp]]) extends Def[DSLPattern]
  case class LowerPattern(xf: Rep[DSLTransformer], op: Rep[DSLOp]) extends Def[DSLPattern]

  def forge_rewrite(ops: Seq[Rep[DSLOp]]): Rep[DSLPattern] = {
    if (ops.length == 1)
      RewritePattern(ops.head)
    else
      RewriteSetPattern(ops.toList)
  }
  def forge_lower(xf: Rep[DSLTransformer], op: Rep[DSLOp]): Rep[DSLPattern] = LowerPattern(xf, op)


  /**
   * Analysis (metadata propagation) rules
   */
  abstract class AnalysisRule
  case class PropagationRule(rule: Rep[String]) extends AnalysisRule
  case class UpdateRule(index: Int, rule: Rep[String]) extends AnalysisRule

  /*def forge_analysis_propagates(az: Rep[DSLAnalyzer], op: Rep[DSLOp], rule: Rep[String]) = {
    Analyzers
  }
  def forge_analysis_updates(az: Rep[DSLAnalyzer], op: Rep[Int], index: Int, rule: Rep[String]) = {

  }*/

  case class AnalysisRules(patterns: HashMap[Rep[DSLOp],List[AnalysisRule]])
  object AnalysisRules {
    def empty = AnalysisRules(HashMap[Rep[DSLOp],List[AnalysisRule]]())
  }

  /**
   * Lowering/Rewrite (transformation) rules
   */
  case class SimpleRule(rule: Rep[String]) extends DSLRule
  case class PatternRule(pattern: List[String], rule: Rep[String], commutative: Boolean) extends DSLRule
  case class ForwardingRule(rule: Rep[String]) extends DSLRule


  def forge_rule(rule: Rep[String]) = SimpleRule(rule)
  def forge_forwarding(rule: Rep[String]) = ForwardingRule(rule)
  def forge_pattern(pattern: List[String], rule: Rep[String], commutative: Boolean)
    = PatternRule(pattern, rule, commutative)

  case class TraversalRules(rules: HashMap[Exp[DSLOp], List[DSLRule]]) {
    def contains(op: Rep[DSLOp]) = rules.contains(op)
  }
  object TraversalRules {
    def empty = TraversalRules(HashMap[Rep[DSLOp],List[DSLRule]]())
  }

  // Create or append rules. Note that definition ordering must be maintained for pattern matching!
  // TODO: Should this return something besides Rep[Unit]?
  def forge_using(pattern: Rep[DSLPattern], rule: DSLRule)(implicit ctx: SourceContext): Rep[Unit] = {

    // Check that the user doesn't give two simple rules for one op pattern
    // TODO: Better stringify method for patterns for warnings/errors
    def append_rule(oldRules: List[DSLRule]) = {
      if (oldRules.exists(_.isInstanceOf[ForwardingRule])) {
        warn("Cannot define rewrite rule on pattern " + pattern + " - pattern already has a forwarding rule")
        oldRules
      }
      else rule match {
        case rule: ForwardingRule =>
          warn("Pattern " + pattern + " has a forwarding rule and other rewrite rules defined on it")
          List(rule)
        case rule: PatternRule => oldRules :+ rule
        case rule: SimpleRule =>
          if (oldRules.exists(_.isInstanceOf[SimpleRule])) err("Pattern " + pattern + " already has a simple rule")
          else oldRules :+ rule
      }
    }

    pattern match {
      case Def(RewriteSetPattern(ops)) => ops.foreach(op => forge_using(forge_rewrite(op), rule))
      case Def(RewritePattern(op)) if !Rewrites.contains(op) => Rewrites(op) = List(rule) // Create
      case Def(RewritePattern(op)) => Rewrites(op) = append_rule(Rewrites(op))
      case Def(LowerPattern(xf, op)) =>
        val rules = Transformers(xf).rules
        if (!rules.contains(op)) rules(op) = List(rule)
        else rules(op) = append_rule(rules(op))
    }
    ()
  }

  /**
   * IR Definitions
   **/
  case class Transform(name: String, isExtern: Boolean) extends Def[DSLTransformer]
  def forge_transformer(name: String, isExtern: Boolean) = {
    val xf: Exp[DSLTransformer] = Transform(name,isExtern)
    if (!Transformers.contains(xf)) {
      Transformers += xf -> TraversalRules.empty
      Traversals += xf
    }
    xf
  }

  case class Analyze(name: String, isExtern: Boolean) extends Def[DSLAnalyzer]
  def forge_analyzer(name: String, isExtern: Boolean) = {
    val az: Exp[DSLAnalyzer] = Analyze(name,isExtern)
    if (!Analyzers.contains(az)) {
      Analyzers += az -> AnalysisRules.empty
      Traversals += az
    }
    az
  }

  def forge_schedule(traversal: Rep[DSLTraversal]) = {
    TraversalSchedule += traversal
    ()
  }

  case class Meta(name: String, tpePars: List[Rep[TypePar]]) extends Def[DSLMetadata]
  def forge_metadata(name: String, tpePars: List[Rep[TypePar]]) = {
    val md: Exp[DSLMetadata] = Meta(name,tpePars)
    if (!Tpes.exists(_.name == md.name)) Tpes += md
    md
  }
  // TODO: Needed?
  def forge_lookup_metadata(name: String): Rep[DSLMetadata] = {
    val md = Tpes.find(t => t.name == name && isMetaType(t))
    if (md.isEmpty)
      err("No metadata type found with name " + name)
    md.get.asInstanceOf[Rep[DSLMetadata]]
  }

  case class MetaOps(
    val meet: HashMap[MetaMeet,Rep[String]],
    val canMeet: HashMap[MetaMeet,Rep[String]],
    var matches: Option[Rep[String]],
    var complete: Option[Rep[String]]
  )
  object MetaOps {
    def empty = MetaOps(HashMap[MetaMeet,Rep[String]](), HashMap[MetaMeet,Rep[String]](), None, None)
  }

  def forge_meet(grp: Rep[DSLGroup], func: MetaMeet, rule: Rep[String]): Rep[Unit] = {
    if (!isMetaType(grp))
      err("Meet operations can only be defined on metadata types")

    val m = grp.asInstanceOf[Rep[DSLMetadata]]

    if (!MetaImpls.contains(m))
      MetaImpls(m) = MetaOps.empty

    if (MetaImpls(m).meet.contains(func))
      warn("Overwriting meet rule on metadata type " + m.name + " for meet function " + func)

    MetaImpls(m).meet += func -> rule
    ()
  }

  /*case class MetaFields(fields: Seq[(String, Rep[DSLType])]) extends Def[DSLMetaFields]
  def forge_metadata_fields(meta: Rep[DSLMetadata], fields: Seq[(String, Rep[DSLType])]) = {
    val data = MetaFields(fields)
    if (MetaStructs.contains(meta)) err("Data fields already defined for metadata " + meta.name)
    else MetaStructs(meta) = data
    ()
  }*/

}