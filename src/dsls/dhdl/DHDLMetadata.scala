package ppl.dsl.forge
package dsls
package dhdl

trait DHDLMetadata {
  this: DHDLDSL =>

  def importDHDLMetadata () = {
    val T = tpePar("T")

    val RegTpe    = lookupTpe("RegTpe", stage=compile)
    val PipeStyle = lookupTpe("PipeStyle", stage=compile)
    val Reg       = lookupTpe("Reg")
    val Pipeline  = lookupTpe("Pipeline")
    val Idx       = lookupAlias("SInt")
    val Tile      = lookupTpe("Tile")
    val Range     = lookupTpe("Range")

    /* Static multidimension dims and size */
    val MDims = metadata("MDims", "dims" -> SList(SInt))
    val dimsOps = metadata("dimsOf")

    onMeet (MDims) ${ this }
    internal.static (dimsOps) ("update", T, (T, SList(SInt)) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDims($1)) }
    internal.static (dimsOps) ("apply", T, T :: SList(SInt)) implements composite ${ meta[MDims]($0).get.dims }
    internal.direct (dimsOps) ("dimOf", T, (T, SInt) :: SInt) implements composite ${ dimsOf($0).apply($1) }
    //internal.direct (dimsOps) ("rankOf", T, T :: SInt) implements composite ${ dimsOf($0).length }

    val sizeOps = metadata("sizeOf")
    internal.static (sizeOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDims(List($1))) }
    internal.static (sizeOps) ("apply", T, T :: SInt) implements composite ${ dimsOf($0).reduce{_*_} }


    /* Dynamic multidimension size */
    val MDynamicSize = metadata("MDynamicSize", "dims" -> SList(Idx))
    val dynamicSizeOps = metadata("symDimsOf")

    onMeet(MDynamicSize) ${ this }
    internal.static (dynamicSizeOps) ("update", T, (T, SList(Idx)) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDynamicSize($1)) }
    internal.static (dynamicSizeOps) ("apply", T, T :: SList(Idx)) implements composite ${ meta[MDynamicSize]($0).get.dims }

    /* Name of a node */
    val MName = metadata("MName", "name" -> SString)
    val nameOps = metadata("nameOf")
    onMeet (MName) ${ this }
    internal.static (nameOps) ("update", T, (T, SString) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MName($1)) }
    internal.static (nameOps) ("apply", T, T :: SString) implements composite ${
      meta[MName]($0) match {
        case Some(n) => n.name
        case None => ""
      }
    }

    /* Is Double Buffer */
    val MDblBuf = metadata("MDblBuf", "isDblBuf" -> SBoolean)
    val dblBufOps = metadata("isDblBuf")
    onMeet (MDblBuf) ${ this }
    internal.static (dblBufOps) ("update", T, (T, SBoolean) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MDblBuf($1)) }
    internal.static (dblBufOps) ("apply", T, T :: SBoolean) implements composite ${ meta[MDblBuf]($0).get.isDblBuf }

    /* Register Type  */
    val MRegTpe = metadata("MRegTpe", "regTpe" -> RegTpe)
    val regTpeOps = metadata("regType")
    onMeet (MRegTpe) ${ this }
    internal.static (regTpeOps) ("update", T, (T, RegTpe) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MRegTpe($1)) }
    internal.static (regTpeOps) ("apply", T, T :: RegTpe) implements composite ${ meta[MRegTpe]($0).get.regTpe }

    /* Register Initial Value */
    val MRegInit = metadata("MRegInit", "value" -> MAny)
    val regReset = metadata("resetValue")
    onMeet (MRegInit) ${ this }
    internal.static (regReset) ("update", T, (Reg(T), T) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MRegInit($1)) }
    internal.static (regReset) ("apply", T, Reg(T) :: T) implements
      composite ${ meta[MRegInit]($0).get.value.asInstanceOf[Rep[T]] }

    /* Parallelization Factor  */
    val MPar = metadata("MPar", "par" -> SInt)
    val parOps = metadata("par")
    onMeet (MPar) ${ this }
    internal.static (parOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
    composite ${ setMetadata($0, MPar($1)) }
    internal.static (parOps) ("apply", T, T :: SInt) implements composite ${
      meta[MPar]($0) match {
        case Some(p) => p.par
        case None => 1
      }
    }

    /* Number of Banks  */
    val MBank = metadata("MBank", "nBanks" -> SInt)
    val bankOps = metadata("banks")
    //TODO:
    onMeet (MBank) ${ this }
    internal.static (bankOps) ("update", T, (T, SInt) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MBank($1)) }
    internal.static (bankOps) ("apply", T, T :: SInt) implements composite ${ meta[MBank]($0).get.nBanks }

    /* Pipeline style */
    val MPipeType = metadata("MPipeType", "tpe" -> PipeStyle)
    val styleOps = metadata("styleOf")
    onMeet (MPipeType) ${ this }
    internal.static (styleOps) ("update", Nil, (Pipeline, PipeStyle) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MPipeType($1)) }
    internal.static (styleOps) ("apply", Nil, Pipeline :: PipeStyle) implements composite ${ meta[MPipeType]($0).get.tpe }


    /* Range is single dimension */
    val MUnitRange = metadata("MUnitRange", "isUnit" -> SBoolean)
    val unitOps = metadata("isUnit")
    onMeet (MUnitRange) ${ this }
    internal.static (unitOps) ("update", Nil, (MAny, SBoolean) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MUnitRange($1)) }
    internal.static (unitOps) ("apply", Nil, MAny :: SBoolean) implements composite ${ meta[MUnitRange]($0).get.isUnit }

    /* Tile Offsets */
    val MTileRanges = metadata("MTileRanges", "ranges" -> SList(Range))
    val rangesOps = metadata("rangesOf")
    onMeet (MTileRanges) ${ this }
    internal.static (rangesOps) ("update", T, (Tile(T), SList(Range)) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MTileRanges($1)) }
    internal.static (rangesOps) ("apply", T, Tile(T) :: SList(Range)) implements composite ${ meta[MTileRanges]($0).get.ranges }

    /* Parent of a node, which is a controller */
	 	// TODO: confirm with Raghu
		/* Reg: 1. reg.reset=(parent.reset its wen& parent.en) 2. reg.wen=din.parent.en */
	 	/* Counter: parent is its counterchain */
	 	/* Pipe/Metapipe/Sequential/Parallel: every node (includeing primitive nodes) inside the
		 * controller has the controller as its parent*/ //TODO: is this necessary?
    val MParent = metadata("MParent", "parent" -> MAny)
    val parentOps = metadata("parentOf")
    onMeet (MParent) ${ this }
    internal.static (parentOps) ("update", T, (T, MAny) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MParent($1)) }
    internal.static (parentOps) ("apply", T, T :: SOption(MAny)) implements composite ${
    	meta[MParent]($0) match {
    	  case Some(p) => Some(p.parent)
    	  case None => None
    	}
		}

    /* A list of ctrl nodes inside current ctrl nodes. Order matters for sequential */
	 	//TODO: need to confirm with Raghu whether ctrl node includes counterchain. looks like it
		// it doesn't 
		// It look like only sequential, metapipe, parallel, blockreduce? need to fill in this metadata 
    val MChildren = metadata("MChildren", "children" -> SList(MAny))
    val childrenOps = metadata("childrenOf")
    onMeet (MChildren) ${ this }
    internal.static (childrenOps) ("update", T, (T, SList(MAny)) :: MUnit, effect = simple) implements
      composite ${ setMetadata($0, MChildren($1)) }
    internal.static (childrenOps) ("apply", T, T :: SList(MAny)) implements composite ${
    	meta[MChildren]($0) match {
    	  case Some(p) => p.children
    	  case None => Nil 
    	}
		}

		/* MaxJ Codegen Helper Functions */
    val maxjgrp = grp("maxjGrp")
		/* Not real metadata but need to be globally accessable */
    val maxjmeta = metadata("maxjMeta")
    internal.direct (maxjgrp) ("maxJPreG", Nil, SInt :: SString) implements composite ${
      if ( $0 == 1 ) "DFEVar"
      else "DFEVector<DFEVar>"
    }
    internal.direct (maxjmeta) ("maxJPre", T, T :: SString) implements composite ${
      maxJPreG(par( $0 ))
    }
		internal.direct (maxjmeta) ("tpstr", T, SInt :: SString) implements composite 	${
			tpstrG[T]( $0 )
		}
		internal.direct (maxjgrp) ("tpstrG", T, SInt :: SString) implements composite 	${
			val scalart = if (isFixPtType(manifest[T])) {
				val s = sign(manifest[T].typeArguments(0))
				val d = nbits(manifest[T].typeArguments(1))
				val f = nbits(manifest[T].typeArguments(2))
				if (s) "dfeFixOffset( "+ (d+f) + "," + f + ", SignMode.TWOSCOMPLEMENT)"
				else "dfeFixOffset("+ (d+f) + "," + f + ", SignMode.UNSIGNED)"
			} else if (isFltPtType(manifest[T])) {
				val e = nbits(manifest[T].typeArguments(0))
				val m = nbits(manifest[T].typeArguments(1))
				"dfeFloat(" + e + "," + m + ")"
			} else if (isBitType(manifest[T])) {
				"TODO"
			} else {
				//throw new Exception("Unknown type " + manifest[T])
				""
			}
			if ( $0 > 1) { 
				"new DFEVectorType<DFEVar>(" + scalart + "," + $0
			} else {
				scalart
			}
		}

	}
}
