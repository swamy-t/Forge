package dhdl.compiler.ops

import scala.reflect.{Manifest,SourceContext}

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait PIRScheduleAnalysisExp extends NodeMetadataOpsExp with ReductionAnalysisExp {
  this: DHDLExp =>

  sealed abstract class MemoryMode
  case object MemLoad extends MemoryMode { override def toString() = "TileLoad" }
  case object MemStore extends MemoryMode { override def toString() = "TileStore" }
  case object MemScatter extends MemoryMode { override def toString() = "Scatter" }
  case object MemGather extends MemoryMode { override def toString() = "Gather" }

  // Inter-CU communication
  sealed abstract class GlobalMem(val name: String)
  case class Offchip(override val name: String) extends GlobalMem(name)
  case class MemCtrl(override val name: String, region: Offchip, mode: MemoryMode) extends GlobalMem(name)
  case class InputArg(override val name: String) extends GlobalMem(name)
  case class OutputArg(override val name: String) extends GlobalMem(name)
  case class ScalarMem(override val name: String) extends GlobalMem(name)
  case class VectorMem(override val name: String) extends GlobalMem(name)
  case class TileTxVector(override val name: String) extends GlobalMem(name)

  case class ScalarIn(name: String, mem: GlobalMem)
  case class ScalarOut(name: String, mem: GlobalMem)
  case class VectorIn(name: String, mem: GlobalMem)
  case class VectorOut(name: String, mem: GlobalMem)

  // Intra-CU communication
  sealed abstract class LocalMem
  case class ReduceReg(producer: Int) extends LocalMem
  case class TempReg(producer: Int) extends LocalMem
  case class InputReg(in: ScalarIn) extends LocalMem
  case class OutputReg(producer: Int, out: ScalarOut) extends LocalMem
  case class InputMem(mem: PIRMemory) extends LocalMem
  case class CounterReg(cchain: PIRCounterChain, idx: Int) extends LocalMem
  case class ConstReg(const: String) extends LocalMem

  // TODO: This is VERY redundant with PIR
  sealed abstract class PIROp
  case object ALUMux extends PIROp { override def toString() = "Mux" }
  case object Bypass extends PIROp
  case object FixAdd extends PIROp
  case object FixSub extends PIROp
  case object FixMul extends PIROp
  case object FixDiv extends PIROp
  case object FltAdd extends PIROp
  case object FltSub extends PIROp
  case object FltMul extends PIROp
  case object FltDiv extends PIROp

  sealed abstract class PIRStage
  case class DefStage(op: Exp[Any], isReduce: Boolean = false, isWrite: Boolean = false) extends PIRStage
  case class PseudoStage(op: PIROp, inputs: List[Exp[Any]], isReduce: Boolean, isWrite: Boolean) extends PIRStage

  case class Stage(op: PIROp, inputs: List[LocalMem], var out: LocalMem) extends PIRStage
  case class ReduceStage(op: PIROp) extends PIRStage

  sealed abstract class ComputeUnit(val name: String, val parent: Option[ComputeUnit]) {
    var cchains: Set[PIRCounterChain] = Set.empty
    var iterators: Map[Exp[Any], (PIRCounterChain, Int)] = Map.empty
    var srams: List[PIRMemory] = Nil
    var stages: List[PIRStage] = Nil
    var scalarIn: List[ScalarIn] = Nil   // locally read, remotely written registers
    var scalarOut: List[ScalarOut] = Nil // locally written, remotely read registers
    var vectorIn: List[VectorIn] = Nil   // locally read, remotely written buffers
    var vectorOut: List[VectorOut] = Nil // locally written, remotely read buffers

    def dumpString = s"""  cchains = ${cchains.mkString(", ")}
  iters   = ${iterators.mkString(", ")}
  srams   = ${srams.mkString(", ")}
  stages  = ${if (stages.isEmpty) "" else stages.mkString("\n    ","\n    ","")}
  scIns   = ${scalarIn.mkString(", ")}
  scOuts  = ${scalarOut.mkString(", ")}
  vecIns  = ${vectorIn.mkString(", ")}
  vecOuts = ${vectorOut.mkString(", ")}"""
}

  case class BasicComputeUnit(
    override val name: String,
    override val parent: Option[ComputeUnit],
    val tpe: ControlType
  ) extends ComputeUnit(name,parent) {
    override def dumpString = s"""BasicComputeUnit($name, $parent, $tpe){
${super.dumpString}
}"""
  override def toString() = s"BasicComputeUnit($name, ${parent.map(_.name)})"
  }

  case class TileTransferUnit(
    override val name: String,
    override val parent: Option[ComputeUnit],
    val ctrl: MemCtrl,
    val mode: MemoryMode
  ) extends ComputeUnit(name,parent) {
    override def dumpString = s"""TileTransferUnit($name, $parent, $ctrl, $mode){
${super.dumpString}
}"""
    override def toString() = s"TileTransferUnit($name, ${parent.map(_.name)}, $ctrl, $mode)"
  }

  // TODO: Parallelism?
  case class PIRCounter(name: String, start: Exp[Any], end: Exp[Any], stride: Exp[Any], par: Exp[Any])

  sealed abstract class PIRCounterChain(val name: String)
  case class CounterChainCopy(override val name: String, owner: ComputeUnit) extends PIRCounterChain(name)
  case class CounterChainInstance(override val name: String, ctrs: List[PIRCounter]) extends PIRCounterChain(name)

  case class PIRMemory(
    name: String,
    size: Int,
    var vector: Option[GlobalMem] = None,
    var readAddr: Option[Exp[Any]] = None,
    var writeAddr: Option[Exp[Any]] = None
  )

}
