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

		val Counter = tpe("Counter")
		data (Counter, ("_name", MString), ("_min", FixPt), ("_max", FixPt), ("_step", FixPt), ("_val", FixPt))
		static (Counter) ("apply", Nil, MethodSignature(List(("name", MString, "unit(\"\")"),
			                                               ("min", FixPt, "unit(0)"),
																										 ("max", FixPt), 
																										 ("step", FixPt, "unit(1)")),
																								Counter), effect=mutable) implements allocates(Counter,
			${$name}, ${$min}, ${$max}, ${$step}, ${ unit(0) })
		static (Counter) ("apply", Nil, MethodSignature(List(("par", SInt),
																										 ("name", MString),
			                                               ("min", FixPt),
																										 ("max", FixPt), 
																										 ("step", FixPt)),
																								Counter), effect=mutable) implements
		redirect ${ Counter.apply($name, $min, $max, $step) }
		static (Counter) ("apply", Nil, (("max", FixPt), ("step", FixPt)) :: Counter, effect=mutable) implements
		redirect ${ Counter.apply(unit(""), unit(0), $max, $step) }
		static (Counter) ("apply", Nil, (("par", SInt), ("max", FixPt), ("step", FixPt)) :: Counter, effect=mutable) implements
		redirect ${ Counter.apply($par, unit(""), unit(0), $max, $step) }

		val CounterOps = withTpe(Counter)
		CounterOps {
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

		val CounterChain = tpe("CounterChain")
		data (CounterChain, ("_chain", MArray(Counter)))
    internal (CounterChain) ("ctrchain_from_array", Nil, MArray(Counter) :: CounterChain,effect=mutable) implements allocates(CounterChain, ${$0})
		static (CounterChain) ("apply", Nil, varArgs(Counter) :: CounterChain) implements composite ${
      val array = array_empty[Counter](unit($0.length))
      val ctrchain = ctrchain_from_array(array)
      for (i <- 0 until $0.length) { ctrchain(i) = $0.apply(i) }
			val ictrchain = ctrchain.unsafeImmutable
			size(ictrchain) = $0.length::Nil
			ictrchain
    }
		val CounterChainOps = withTpe(CounterChain)
		CounterChainOps {
			infix ("mkString") (Nil :: MString) implements composite ${
				unit("ctrchain[") +
				array_mkstring[String](
					array_map[Counter,String]($self.chain, c => c.mkString), ",") +
				unit("]")
			}
			infix ("chain") (Nil :: MArray(Counter)) implements getter(0, "_chain")
      infix ("update") ((MInt,Counter) :: MUnit, effect = write(0)) implements composite ${ array_update($0.chain, $1, $2) }
			infix ("length") (Nil :: MInt) implements composite ${ $self.chain.length }
		}

		val Pipe = tpe("Pipe")
		data (Pipe, ("_ctrs", CounterChain)) //TODO: Modify pipe to keep track of nodes inside
		static (Pipe) ("apply", Nil, (CounterChain) :: Pipe) implements
		allocates(Pipe, ${$0})

		val loop = internal (CtrlOps) ("loop", Nil, (("ctr", Counter), ("lambda", FixPt ==> MUnit)) :: MUnit)
		impl (loop) (composite ${
			var i = $ctr.min
			while (i < $ctr.max) {
				$lambda (i)
				i = i + $ctr.step
			}
		})

		//TODO: Won't work here until have metadata
		val pipe_map = static (Pipe) ("apply", Nil, (("pipelined", MBoolean), ("ctrs", CounterChain), ("mapFunc", varArgs(FixPt) ==> MUnit)) :: Pipe)
		impl (pipe_map) (composite ${
			val ctrSize = getSize0($ctrs)
			def recPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, { (i:Rep[FixPt]) => $mapFunc( i+:idxs )} )
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recPipe(idx - 1, i+:idxs) ))
				}
			}
			val pipe = Pipe( $ctrs)
			recPipe( ctrSize - 1, Seq.empty[Rep[FixPt]] )
			pipe
		})
		static (Pipe) ("apply", Nil, (("ctrs", CounterChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: Pipe) implements redirect ${
			Pipe(unit(true), $ctrs, $mapFunc)
		}
		direct (Pipe) ("PipeSeq", Nil, (("ctrs", CounterChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: Pipe) implements redirect ${
			Pipe(unit(false), $ctrs, $mapFunc)
		}

		val pipe_reduce = static (Pipe) ("apply", T, MethodSignature(List(("pipelined", MBoolean),
			("ctrs", CounterChain), ("accum", Reg(T)), ("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)), Pipe))
		impl (pipe_reduce) (composite ${
			def recPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, ( (i:Rep[FixPt]) => $accum.write($reduceFunc($accum.value, $mapFunc(i+:idxs))) ))
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recPipe(idx - 1, i+:idxs) ))
				}
			}
			val ctrSize = getSize0($ctrs)
			val pipe = Pipe( $ctrs)
			$accum.reset
			recPipe( ctrSize - 1, Seq.empty[Rep[FixPt]] )
			pipe
		})

		/* MetaPipeline */
		val MetaPipe = tpe("MetaPipe")
		data (MetaPipe, ("_ctrs", CounterChain)) //TODO: Modify pipe to keep track of nodes inside
		static (MetaPipe) ("apply", Nil, (CounterChain) :: MetaPipe) implements
		allocates(MetaPipe, ${$0})

		/* MetaPipe Map  */
		val meta_map = static (MetaPipe) ("apply", Nil, (("pipelined", MBoolean), ("ctrs", CounterChain),
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
			val ctrSize = getSize0($ctrs)
			val metaPipe = MetaPipe( $ctrs)
			recMetaPipe( ctrSize - 1, Seq.empty[Rep[FixPt]] )
			metaPipe
		})
		static (MetaPipe) ("apply", Nil, (("ctrs", CounterChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: MetaPipe) implements redirect ${
				MetaPipe(unit(true), $ctrs, $mapFunc)
			}
		direct (MetaPipe) ("Sequential", Nil, (("ctrs", CounterChain),
			("mapFunc", varArgs(FixPt) ==> MUnit)) :: MetaPipe) implements redirect ${
				MetaPipe(unit(false), $ctrs, $mapFunc)
			}

		/* MetaPipe Reduction */
	 /*
		val meta_reduce = static (MetaPipe) ("apply", T, MethodSignature(List(("ctrSize", SInt),
			("pipelined", MBoolean), ("ctrs", CounterChain), ("accum", Reg(T)),
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
		*/
		val meta_reduce = static (MetaPipe) ("apply", T, MethodSignature(List(
			("pipelined", MBoolean), ("ctrs", CounterChain), ("accum", Reg(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)), MetaPipe))
		impl (meta_reduce) (redirect ${
			val wrapMapFun = (idxs:Seq[Rep[FixPt]]) => {
				Seq($mapFunc(idxs:_*))
			}
			MetaReduceMany[T]($pipelined, $ctrs, Seq($accum), Seq(reduceFunc), wrapMapFun)
		})
		direct (MetaPipe) ("Sequential", T, MethodSignature(List(("ctrs", CounterChain), ("accum", Reg(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> T)),MetaPipe)) implements
		redirect ${
			MetaPipe[T](unit(false), $ctrs, $accum, $reduceFunc, $mapFunc)
		}
		val SSeq = tpe("scala.Seq", T, stage=compile)
		val meta_reduce_many = direct (MetaPipe) ("MetaReduceMany", T, MethodSignature(List(
			("pipelined", MBoolean), ("ctrs", CounterChain), ("accums", SSeq(Reg(T))),
			("reduceFuncs", SSeq((T, T) ==> T)) , ("mapFunc", varArgs(FixPt) ==> SSeq(T))), MetaPipe))
		impl (meta_reduce_many) (composite ${
			def recMetaPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, { case i => 
						val results = $mapFunc(i+:idxs)
						$accums.zipWithIndex.foreach{case (accum, accIdx) =>
							val reduceFunc = reduceFuncs(accIdx)
							accum.write(reduceFunc(accum.value, results(accIdx))) 
						}
					})
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recMetaPipe(idx - 1, i+:idxs) ))
				}
			}
			val ctrSize = getSize0($ctrs)
			val metaPipe = MetaPipe( $ctrs)
			$accums.foreach(accum => accum.reset)
			recMetaPipe(ctrSize - 1, Seq.empty[Rep[FixPt]] )
			metaPipe
		})

		//TODO:FIX this
		val BRAM = lookupTpe("BRAM")
		val bram_reduce_many = direct (MetaPipe) ("BramReduceMany", T, MethodSignature(List( 
			("pipelined", MBoolean), ("ctrs", CounterChain), ("brams", SSeq(BRAM(T))),
			("reduceFuncs", SSeq((T, T) ==> T)) , ("mapFunc", varArgs(FixPt) ==> SSeq(BRAM(T)))), MetaPipe))
		impl (bram_reduce_many) (composite ${
			val bramSize = size($brams(0)).size.reduce(_*_) 
			def recMetaPipe (idx:Int, idxs:Seq[Rep[FixPt]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(unit(idx))
				if (idx == 0) {
					loop(ctr, { case i => 
						val resultBms = $mapFunc(i+:idxs)
						$brams.zipWithIndex.foreach{case (bram, accIdx) =>
							val reduceFunc = reduceFuncs(accIdx)
							val bramCtr = CounterChain(Counter(max=bramSize))
							Pipe(true, bramCtr, {case i::_ =>
								bram.st(i, reduceFunc(bram.ld(i), resultBms(accIdx).ld(i))) 
							})
						}
					})
				} else {
					loop(ctr, ( (i:Rep[FixPt]) => recMetaPipe(idx - 1, i+:idxs) ))
				}
			}
			val metaPipe = MetaPipe( $ctrs)
			//$brams.foreach(bram => bram.reset)
			val ctrSize = getSize0($ctrs)
			recMetaPipe( ctrSize - 1, Seq.empty[Rep[FixPt]] )
			metaPipe
		})
		val bram_reduce = direct (MetaPipe) ("BramReduce", T, MethodSignature(List( 
			("pipelined", MBoolean), ("ctrs", CounterChain), ("bram", BRAM(T)),
			("reduceFunc", (T, T) ==> T) , ("mapFunc", varArgs(FixPt) ==> BRAM(T))), MetaPipe))
		impl (bram_reduce) (redirect ${
			val wrapMapFun = (idxs:Seq[Rep[FixPt]]) => {
				Seq($mapFunc(idxs:_*))
			}
			BramReduceMany[T]($pipelined, $ctrs, Seq($bram), Seq(reduceFunc), wrapMapFun)
		})
		/*
		*/

		/* MetaPipe Parallel */
		val meta_parallel = direct (MetaPipe) ("Parallel", Nil, ("func", MThunk(MUnit)) :: MetaPipe) 
		impl (meta_parallel) (composite ${
			val metaPipe = MetaPipe( CounterChain(Counter(max=unit(1))))
			$func
			metaPipe
		})

		/* MetaPipe 1 iteration */
		val meta_1iter = static (MetaPipe) ("apply", Nil, ("func", MThunk(MUnit)) :: MetaPipe) 
		impl (meta_1iter) (composite ${
			val metaPipe = MetaPipe( CounterChain(Counter(max=unit(1))))
			$func
			metaPipe
		})

	}
}
