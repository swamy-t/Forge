package dhdl.library.classes

import scala.annotation.unchecked.uncheckedVariance
import scala.reflect.{Manifest,SourceContext}

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.library._
import dhdl.library.classes._

trait PipeTemplateWrapper {
  this: DHDLBase with DHDLClasses =>

  // TODO: Better way to do this besides recursion?
  def loop(cchain: Rep[CounterChain], idx: Int, indices: List[FixPt[Signed,B32,B0]], func: Rep[Indices] => Rep[Unit]): Rep[Unit] = {
    val ctr = cchain(idx)
    if (idx >= cchain.length - 1) {
      for (i <- ctr) { func(indices_create(indices :+ i)) }
    }
    else {
      for (i <- ctr) { loop(cchain, idx+1, indices :+ i, func) }
    }
  }

  def pipe_foreach(cchain: Rep[CounterChain], func: Rep[Indices] => Rep[Unit])(implicit ctx: SourceContext): Rep[Pipeline] = {
    loop(cchain, 0, Nil, func)
  }

  def pipe_reduce[T,C[T]](cchain: Rep[CounterChain], accum: Rep[C[T]], func: Rep[Indices] => Rep[T], rFunc: (Rep[T],Rep[T]) => Rep[T])(implicit ctx: SourceContext, __mem: Mem[T,C], __mT: Manifest[T], __cb_hk_0: Manifest[C[T]]): Rep[Pipeline] = {
    def ldFunc(c: Rep[C[T]], i: Rep[Indices]): Rep[T] = __mem.ld(c, i)
    def stFunc(c: Rep[C[T]], i: Rep[Indices], x: Rep[T]): Rep[Unit] = __mem.st(c, i, x)

    loop(cchain, 0, Nil, {i: Rep[Indices] => stFunc(accum, i, rFunc(ldFunc(accum, i), func(i))) })
  }

  def counterchain_new(counters: List[Rep[Counter]])(implicit ctx: SourceContext): Rep[CounterChain] = {
    counters.toArray.asInstanceOf[Rep[CounterChain]]
  }
}
