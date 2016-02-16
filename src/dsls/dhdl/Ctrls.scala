package ppl.dsl.forge
package dsls
package dhdl

trait CtrlOps {
	this: DHDLDSL =>

	def importCtrls () = {

		val T = tpePar("T")
		val Reg = lookupTpe("Reg")
		val FixPt = lookupTpe("Long")
		val CtrlOps = grp("Ctrls")

		val Ctr = tpe("Ctr")
		data (Ctr, ("_name", MString), ("_min", FixPt), ("_max", FixPt), ("_step", FixPt), ("_val", FixPt))
		static (Ctr) ("apply", Nil, MethodSignature(List(("name", MString, "unit(\"\")"),
			                                               ("min", FixPt, "unit(0)"),
																										 ("max", FixPt), 
																										 ("step", FixPt, "unit(1)")),
																								Ctr), effect=mutable) implements allocates(Ctr,
			${$name}, ${$min}, ${$max}, ${$step}, ${ unit(0) })
		static (Ctr) ("apply", Nil, MethodSignature(List(("par", SInt),
																										 ("name", MString),
			                                               ("min", FixPt),
																										 ("max", FixPt), 
																										 ("step", FixPt)),
																								Ctr), effect=mutable) implements
		redirect ${ Ctr.apply($name, $min, $max, $step) }
		static (Ctr) ("apply", Nil, (("max", FixPt), ("step", FixPt)) :: Ctr, effect=mutable) implements
		redirect ${ Ctr.apply(unit(""), unit(0), $max, $step) }
		static (Ctr) ("apply", Nil, (("par", SInt), ("max", FixPt), ("step", FixPt)) :: Ctr, effect=mutable) implements
		redirect ${ Ctr.apply($par, unit(""), unit(0), $max, $step) }

		val CtrOps = withTpe(Ctr)
		CtrOps {
			infix ("mkString") (Nil :: MString) implements composite ${
				unit("ctr(") +
        unit("name:") + $self.name +
			  unit(", min:") + $self.min +
			  unit(", max:") + $self.max +
			  unit(", step:") + $self.step +
				unit(")")
      }
			infix ("name") (Nil :: MString) implements getter(0, "_name")
			infix ("min") (Nil :: FixPt) implements getter(0, "_min")
			infix ("max") (Nil :: FixPt) implements getter(0, "_max")
			infix ("step") (Nil :: FixPt) implements getter(0, "_step")
		}

		val CtrChain = tpe("CtrChain")
		//val MCtrChain = metadata("MCtrChain", ("size",SInt))
		//meet (MCtrChain) ${ this }
		//compiler.static (MCtrChain) ("update", Nil, (MAny, SInt) :: MUnit, effect = simple) implements
		//composite ${ setMetadata($0, MCtrChain($1)) }
		//compiler.static (MCtrChain) ("apply", Nil, MAny :: SInt) implements composite ${
		//meta[MCtrChain]($0).get}

		data (CtrChain, ("_chain", MArray(Ctr)))
    internal (CtrChain) ("ctrchain_from_array", Nil, MArray(Ctr) :: CtrChain,effect=mutable) implements allocates(CtrChain, ${$0})
		static (CtrChain) ("apply", Nil, varArgs(Ctr) :: CtrChain) implements composite ${
      val array = array_empty[Ctr](unit($0.length))
      val ctrchain = ctrchain_from_array(array)
      for (i <- 0 until $0.length) { ctrchain(i) = $0.apply(i) }
			val ictrchain = ctrchain.unsafeImmutable
			//MCtrChain(ictrchain) = $0.length
			ictrchain
    }
		val CtrChainOps = withTpe(CtrChain)
		CtrChainOps {
			infix ("mkString") (Nil :: MString) implements composite ${
				unit("ctrchain[") +
				array_mkstring[String](
					array_map[Ctr,String]($self.chain, c => c.mkString), ",") +
				unit("]")
			}
			infix ("chain") (Nil :: MArray(Ctr)) implements getter(0, "_chain")
      infix ("update") ((MInt,Ctr) :: MUnit, effect = write(0)) implements composite ${ array_update($0.chain, $1, $2) }
			infix ("length") (Nil :: MInt) implements composite ${ $self.chain.length }
		}

		val Pipe = tpe("Pipe")
		data (Pipe, ("_ctrs", CtrChain)) //TODO: Modify pipe to keep track of nodes inside
		static (Pipe) ("apply", Nil, (CtrChain) :: Pipe) implements
		allocates(Pipe, ${$0})

		val loop = internal (CtrlOps) ("loop", Nil, (("ctr", Ctr), ("lambda", FixPt ==> MUnit)) :: MUnit)
		impl (loop) (composite ${
			var i = $ctr.min
			while (i < $ctr.max) {
				$lambda (i)
				i = i + $ctr.step
			}
		})

		//TODO: Won't work here until have metadata
		val pipe_map = static (Pipe) ("apply", Nil, (("ctrSize", SInt), ("pipelined", MBoolean), ("ctrs", CtrChain), ("mapFunc", varArgs(FixPt) ==> MUnit)) :: Pipe)
		impl (pipe_map) (composite ${
			def recPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, { (i:Rep[FixPt]) => $mapFunc( i+:idxs )} )
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recPipe(idx - 1, i+:idxs) ))
				}
			}
			val pipe = Pipe( $ctrs)
			recPipe( $ctrSize - 1, Seq.empty[Rep[FixPt]] )
			pipe
		})
		static (Pipe) ("apply", Nil, (("ctrSize", SInt), ("ctrs", CtrChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: Pipe) implements redirect ${
			Pipe($ctrSize, unit(true), $ctrs, $mapFunc)
		}

		val pipe_reduce = static (Pipe) ("apply", T, MethodSignature(List(("ctrSize", SInt), ("pipelined", MBoolean),
			("ctrs", CtrChain), ("accum", Reg(T)), ("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)), Pipe))
		impl (pipe_reduce) (composite ${
			def recPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, ( (i:Rep[FixPt]) => $accum.write($reduceFunc($accum.value, $mapFunc(i+:idxs))) ))
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recPipe(idx - 1, i+:idxs) ))
				}
			}
			val pipe = Pipe( $ctrs)
			$accum.reset
			recPipe( $ctrSize - 1, Seq.empty[Rep[FixPt]] )
			pipe
		})
		/*
		static (Pipe) ("apply", T, (("ctrSize", SInt), ("ctrs", CtrChain), ("accum", Reg(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)) :: Pipe) implements redirect ${
			Pipe[T]($ctrSize, unit(true), $ctrs, $accum, $reduceFunc, $mapFunc)
		}
		*/

		val MetaPipe = tpe("MetaPipe")
		data (MetaPipe, ("_ctrs", CtrChain)) //TODO: Modify pipe to keep track of nodes inside
		static (MetaPipe) ("apply", Nil, (CtrChain) :: MetaPipe) implements
		allocates(MetaPipe, ${$0})

		val meta_map = static (MetaPipe) ("apply", Nil, (("ctrSize", SInt), ("pipelined", MBoolean), ("ctrs", CtrChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: MetaPipe)
		impl (meta_map) (composite ${
			def recMetaPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, ( (i:Rep[FixPt]) => $mapFunc( i+:idxs )) )
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recMetaPipe(idx - 1, i+:idxs) ))
				}
			}
			val metaPipe = MetaPipe( $ctrs)
			recMetaPipe( $ctrSize - 1, Seq.empty[Rep[FixPt]] )
			metaPipe
		})
		static (MetaPipe) ("apply", Nil, (("ctrSize", SInt), ("ctrs", CtrChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: MetaPipe) implements redirect ${
				MetaPipe.apply($ctrSize, unit(true), $ctrs, $mapFunc)
			}

		val meta_reduce = static (MetaPipe) ("apply", T, MethodSignature(List(("ctrSize", SInt),
			("pipelined", MBoolean), ("ctrs", CtrChain), ("accum", Reg(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)), MetaPipe))
		impl (meta_reduce) (composite ${
			def recMetaPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, ( (i:Rep[FixPt]) => $accum.write($reduceFunc($accum.value, $mapFunc(i+:idxs))) ))
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recMetaPipe(idx - 1, i+:idxs) ))
				}
			}
			val metaPipe = MetaPipe( $ctrs)
			$accum.reset
			recMetaPipe( $ctrSize - 1, Seq.empty[Rep[FixPt]] )
			metaPipe
		})
		/*
		static (MetaPipe) ("apply", T, MethodSignature(List(("ctrSize", SInt), ("ctrs", CtrChain), ("accum", Reg(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)),MetaPipe)) implements
		redirect ${
			MetaPipe[T]($ctrSize, unit(true), $ctrs, $accum, $reduceFunc, $mapFunc)
		}
		*/

		val meta_parallel = direct (MetaPipe) ("Parallel", Nil, ("func", MThunk(MUnit)) :: MetaPipe) 
		impl (meta_parallel) (composite ${
			val metaPipe = MetaPipe( CtrChain(Ctr(max=unit(1))))
			$func
			metaPipe
		})

		val meta_1iter = static (MetaPipe) ("apply", Nil, ("func", MThunk(MUnit)) :: MetaPipe) 
		impl (meta_1iter) (composite ${
			val metaPipe = MetaPipe( CtrChain(Ctr(max=unit(1))))
			$func
			metaPipe
		})

	}
}
