package ppl.dsl.forge
package dsls
package dhdl

@dsl
trait DHDLMemories {
  this: DHDLDSL =>

  object TMem extends TypeClassSignature {
    def name = "Mem"
    def prefix = "_m"
    def wrapper = None
  }

  def importDHDLMemories() {
    importMemOps()
    importRegs()
    importBRAM()
    importOffChip()
    importTiles()
  }

  // Type class for local memories which can be used as accumulators in reductions
  def importMemOps() {
    val T = tpePar("T")       // data type
    val C = hkTpePar("C", T)  // memory type

    val CounterChain = lookupTpe("CounterChain")
    val Indices      = lookupTpe("Indices")
    val Idx          = lookupAlias("Index")

    val Mem = tpeClass("Mem", TMem, (T, C))
    infix (Mem) ("ld", (T,C), (C, Idx) :: T)
    infix (Mem) ("st", (T,C), (C, Idx, T) :: MUnit, effect = write(0))
    infix (Mem) ("flatIdx", (T,C), (C, Indices) :: Idx)
    infix (Mem) ("iterator", (T,C), (C, SList(MInt)) :: CounterChain)
    infix (Mem) ("zeros", (T,C), varArgs(Idx) :: C, TNum(T))
  }


  // TODO: Better / more correct way of exposing register reset?
  def importRegs() {
    val T = tpePar("T")

    val Reg   = lookupTpe("Reg")
    val FixPt = lookupTpe("FixPt")
    val FltPt = lookupTpe("FltPt")
    val Bit   = lookupTpe("Bit")
    val RegTpe  = lookupTpe("RegTpe", stage=compile)
    val Indices = lookupTpe("Indices")
    val Idx     = lookupAlias("Index")

    // --- Nodes
    val reg_new   = internal (Reg) ("reg_new", T, ("init", T) :: Reg(T), effect = mutable)
    val reg_read  = internal (Reg) ("reg_read", T, ("reg", Reg(T)) :: T, aliasHint = aliases(Nil), effect = simple)
    val reg_write = internal (Reg) ("reg_write", T, (("reg", Reg(T)), ("value", T)) :: MUnit, effect = write(0))
    val reg_reset = internal (Reg) ("reg_reset", T, ("reg", Reg(T)) :: MUnit, effect = write(0))

    // --- Internals
    internal (Reg) ("reg_create", T, (SOption(SString), T, RegTpe) :: Reg(T), effect = mutable) implements composite ${
      val reg = reg_new[T](init = $1)
      $0.foreach{name => nameOf(reg) = name }
      isDblBuf(reg) = false
      regType(reg) = $2
      resetValue(reg) = $1
      reg
    }

    /** @nodoc **/
    direct (Reg) ("readReg", T, ("reg", Reg(T)) :: T) implements composite ${ reg_read($0) }
    /** @nodoc **/
    direct (Reg) ("writeReg", T, (("reg", Reg(T)), ("value", T)) :: MUnit, effect = write(0)) implements composite ${ reg_write($0, $1) }

    val Mem = lookupTpeClass("Mem").get
    val RegMem = tpeClassInst("RegMem", T, TMem(T, Reg(T)))
    infix (RegMem) ("ld", T, (Reg(T), Idx) :: T) implements composite ${ readReg($0) } // Ignore address
    infix (RegMem) ("st", T, (Reg(T), Idx, T) :: MUnit, effect = write(0)) implements composite ${ writeReg($0, $2) }
    infix (RegMem) ("flatIdx", T, (Reg(T), Indices) :: Idx) implements composite ${ 0.as[Index] }
    infix (RegMem) ("iterator", T, (Reg(T), SList(MInt)) :: CounterChain) implements composite ${ CounterChain(Counter(max=1)) }
    infix (RegMem) ("zeros", T, varArgs(Idx) :: Reg(T), TNum(T)) implements composite ${ reg_create(None, zero[T], Regular) }

    // --- API
    /* Reg */
    /** Creates a register with type T and given name **/
    static (Reg) ("apply", T, ("name", SString) :: Reg(T), TNum(T)) implements composite ${ reg_create[T](Some($0), zero[T], Regular) }
    /** Creates an unnamed register with type T **/
    static (Reg) ("apply", T, Nil :: Reg(T), TNum(T)) implements composite ${ reg_create[T](None, zero[T], Regular) }

    UnstagedNumerics.foreach{ (ST,_) =>
      /** Creates a register of type T with given name and reset value **/
      static (Reg) ("apply", T, (("name", SString), ("reset", ST)) :: Reg(T), TNum(T)) implements composite ${ reg_create[T](Some($name), $reset.as[T], Regular) }
      /** Creates an unnamed register with type T and given reset value **/
      static (Reg) ("apply", T, ("reset", ST) :: Reg(T), TNum(T)) implements composite ${ reg_create[T](None, $reset.as[T], Regular) }
    }

    /** Creates a named input argument from the host CPU **/
    direct (Reg) ("ArgIn", T, ("name", SString) :: Reg(T), TNum(T)) implements composite ${ reg_create[T](Some($name), zero[T], ArgumentIn) }
    /** Creates an unnamed input argument from the host CPU **/
    direct (Reg) ("ArgIn", T, Nil :: Reg(T), TNum(T)) implements composite ${ reg_create[T](None, zero[T], ArgumentIn) }

    /** Creats a named output argument to the host CPU **/
    direct (Reg) ("ArgOut", T, ("name", SString) :: Reg(T), TNum(T)) implements composite ${ reg_create[T](Some($name), zero[T], ArgumentOut) }
    /** Creats an unnamed output argument to the host CPU **/
    direct (Reg) ("ArgOut", T, Nil :: Reg(T), TNum(T)) implements composite ${ reg_create[T](None, zero[T], ArgumentOut) }

    val Reg_API = withTpe(Reg)
    Reg_API {
      /** Reads the current value of this register **/
      infix ("value") (Nil :: T) implements redirect ${ readReg($self) }
      /** Creates a writer to this Reg. Note that Regs and ArgOuts can only have one writer, while ArgIns cannot have any **/
      infix (":=") (("x",T) :: MUnit, effect = write(0)) implements composite ${
        if (regType($self) == ArgumentIn) stageError("Writing to an input argument is disallowed")
        reg_write($self, $1)
      }
      /** @nodoc - User register reset is not yet well-defined **/
      infix ("rst") (Nil :: MUnit, effect = write(0)) implements composite ${ reg_reset($self) }
    }

    // TODO: Should warn/error if not an ArgIn?
    /** Enables implicit reading from fixed point type Regs **/
    fimplicit (Reg) ("regFixToFix", (S,I,F), Reg(FixPt(S,I,F)) :: FixPt(S,I,F)) implements redirect ${ readReg($0) }
    /** Enables implicit reading from floating point type Regs **/
    fimplicit (Reg) ("regFltToFlt", (G,E), Reg(FltPt(G,E)) :: FltPt(G,E)) implements redirect ${ readReg($0) }
    /** Enables implicit reading from bit type Regs **/
    fimplicit (Reg) ("regBitToBit", Nil, Reg(Bit) :: Bit) implements redirect ${ readReg($0) }

    // --- Scala Backend
    impl (reg_new)   (codegen($cala, ${ Array($init) }))
    impl (reg_read)  (codegen($cala, ${ $reg.apply(0) }))
    impl (reg_write) (codegen($cala, ${ $reg.update(0, $value) }))
    impl (reg_reset) (codegen($cala, ${
      @ val init = resetValue($reg)
      $reg.update(0, $init)
    }))

    // --- Dot Backend
    impl (reg_new)   (codegen(dot, ${
			@ regType(sym) match {
				@ case Regular =>
					@ if (isDblBuf(sym)) {
							$sym [margin=0, rankdir="LR", label="{<st> | <ld>}" xlabel="$sym "
                    shape="record" color=$dblbufBorderColor style="filled"
                    fillcolor=$regFillColor ]
					@ } else {
							$sym [label= "\$sym" shape="square" style="filled" fillcolor=$regFillColor ]
					@ }
				@ case ArgumentIn =>
        	@ alwaysGen {
            $sym [label=$sym shape="Msquare" style="filled" fillcolor=$regFillColor ]
				  @ }
        @ case ArgumentOut =>
        	@ alwaysGen {
            $sym [label=$sym shape="Msquare" style="filled" fillcolor=$regFillColor ]
			    @ }
      @ }
		}))
    impl (reg_read)  (codegen(dot, ${
			@ emitValDef(sym, reg)
		}))
    impl (reg_write) (codegen(dot, ${
			$value -> $reg
		}))
    impl (reg_reset) (codegen(dot, ${
    }))

    // --- MaxJ Backend
		//reg_new (extern)
    impl (reg_read)  (codegen(maxj, ${
			@	val pre = maxJPre(sym)
			@ val regStr = regType(reg) match {
			@ 	case Regular => quote(reg) + "_hold"
			@ 	case _ => quote(reg)
			@ }
			$pre $sym = $regStr ;
		}))
		//reg_write (extern)
    impl (reg_reset) (codegen(maxj, ${
    }))

  }


  // TODO: Generalize definition of BRAM store to be equivalent to St node in original DHDL?
  // TODO: Should we support a BRAM reset API? How to time this properly? Should this be a composite which creates a Pipe?
  def importBRAM() {
    val T = tpePar("T")
    val BRAM    = lookupTpe("BRAM")
    val Tile    = lookupTpe("Tile")
    val Idx     = lookupAlias("Index")
    val Indices = lookupTpe("Indices")

    // --- Nodes
    val bram_new = internal (BRAM) ("bram_new", T, (("size", Idx), ("zero", T)) :: BRAM(T), effect = mutable)
    val bram_load = internal (BRAM) ("bram_load", T, (("bram", BRAM(T)), ("addr", Idx)) :: T)
    val bram_store = internal (BRAM) ("bram_store", T, (("bram", BRAM(T)), ("addr", Idx), ("value", T)) :: MUnit, effect = write(0), aliasHint = aliases(Nil))
    val bram_reset = internal (BRAM) ("bram_reset", T, (("bram", BRAM(T)), ("zero", T)) :: MUnit, effect = write(0))

    // --- Internals
    internal (BRAM) ("bram_create", T, (SOption(SString), SList(Idx)) :: BRAM(T), TNum(T)) implements composite ${
      val bram = bram_new[T](productTree($1), zero[T])
      if ($1.length < 1) stageError("Cannot create a BRAM with no dimensions")
      dimsOf(bram) = $1
      $0.foreach{name => nameOf(bram) = name }
      isDblBuf(bram) = false
      banks(bram) = 1
      bram
    }

    // TODO: Ask Raghu what the representation in the IR for these should be
    internal (BRAM) ("bram_load_vector", T, (("bram", BRAM(T)), ("ofs",Idx), ("len",Idx), ("par", MInt)) :: Vector(T)) implements composite ${

    }
    internal (BRAM) ("bram_store_vector", T, (("bram", BRAM(T)), ("ofs", Idx), ("vec", Vector(T)), ("par", MInt)) :: MUnit, effect = write(0)) implements composite ${

    }

    /** @nodoc **/
    direct (BRAM) ("bram_load_nd", T, (BRAM(T), SList(Idx)) :: T) implements composite ${
      if ($1.length < 1) stageError("Cannot load from zero indices")
      val addr = calcAddress($1, dimsOf($0))
      bram_load($0, addr)
    }
    /** @nodoc **/
    direct (BRAM) ("bram_store_nd", T, (BRAM(T), SList(Idx), T) :: MUnit, effect = write(0)) implements composite ${
      if ($1.length < 1) stageError("Cannot store to zero indices")
      val addr = calcAddress($1, dimsOf($0))
      bram_store($0, addr, $2)
    }

    /** @nodoc **/
    direct (BRAM) ("bram_calc_addr", T, (BRAM(T), Indices) :: Idx) implements composite ${ calcAddress($1.toList, dimsOf($0)) }

    val Mem = lookupTpeClass("Mem").get
    val BramMem = tpeClassInst("BramMem", T, TMem(T, BRAM(T)))
    infix (BramMem) ("ld", T, (BRAM(T), Idx) :: T) implements composite ${ bram_load_nd($0, List($1)) }
    infix (BramMem) ("st", T, (BRAM(T), Idx, T) :: MUnit, effect = write(0)) implements composite ${ bram_store_nd($0, List($1), $2) }
    infix (BramMem) ("flatIdx", T, (BRAM(T), Indices) :: Idx) implements composite ${ bram_calc_addr($0, $1) }
    infix (BramMem) ("iterator", T, (BRAM(T), SList(MInt)) :: CounterChain) implements composite ${
      val dims = dimsOf($0)
      val pars = List.fill($1.length - dims.length)(param(1)) ++ $1
      val ctrs = dims.zip(pars).map{case (d,p) => Counter(min = 0, max = d, step = 1, par = p) }
      CounterChain(ctrs:_*)
    }
    infix (BramMem) ("zeros", T, varArgs(Idx) :: BRAM(T), TNum(T)) implements composite ${ bram_create(None, $0.toList) }


    // --- API
    /** Creates a BRAM with given name and dimensions. Dimensions must be statically known signed integers (constants or parameters).
     * @param name
     * @param dims
     **/
    static (BRAM) ("apply", T, (SString, varArgs(Idx)) :: BRAM(T), TNum(T)) implements composite ${ bram_create[T](Some($0), $1.toList) }

    /** Creates an unnamed BRAM with given dimensions. Dimensions must be statically known signed integers (constants or parameters).
     * @param dims
     **/
    static (BRAM) ("apply", T, (varArgs(Idx)) :: BRAM(T), TNum(T)) implements composite ${ bram_create[T](None, $0.toList) }

    val BRAM_API = withTpe(BRAM)
    BRAM_API {
      /** Creates a read from this BRAM at the given multi-dimensional address. Number of indices given can either be 1 or the
       * same as the number of dimensions that the BRAM was declared with.
       * @param ii: multi-dimensional address
       **/
      infix ("apply") (varArgs(Idx) :: T) implements composite ${ bram_load_nd($self, $1.toList) }

      /* Store */
      /** Creates a write to this BRAM at the given 1D address.
       * @param i: 1D address
       * @param x: element to be stored to BRAM
       **/
      infix ("update") ((Idx, T) :: MUnit, effect = write(0)) implements composite ${ bram_store_nd($self, List($1), $2) }
      /** Creates a write to this BRAM at the given 2D address. The BRAM must have initially been declared as 2D.
       * @param i: row index
       * @param j: column index
       * @param x: element to be stored to BRAM
       **/
      infix ("update") ((Idx, Idx, T) :: MUnit, effect = write(0)) implements composite ${ bram_store_nd($self, List($1, $2), $3) }
      /** Creates a write to this BRAM at the given 3D address. The BRAM must have initially been declared as 3D.
       * @param i: row index
       * @param j: column index
       * @param k: page index
       * @param x: element to be stored to BRAM
       **/
      infix ("update") ((Idx, Idx, Idx, T) :: MUnit, effect = write(0)) implements composite ${ bram_store_nd($self, List($1, $2, $3), $4) }

      /** Creates a write to this BRAM at the given multi-dimensional address. The number of indices given can either be 1 or the
       * same as the number of dimensions that the BRAM was declared with.
       * @param ii: multi-dimensional index
       * @param x: element to be stored to BRAM
       **/
      infix ("update") ((SSeq(Idx), T) :: MUnit, effect = write(0)) implements composite ${ bram_store_nd($self, $1.toList, $2) }

      /** @nodoc - BRAM reset is not yet well defined **/
      infix ("rst") (Nil :: MUnit, TNum(T), effect = write(0)) implements composite ${ bram_reset($self, zero[T]) }

      /** Creates a tile store from a Tile of an OffChipMem to this BRAM.
       * @param tile
       **/
      infix (":=") (Tile(T) :: MUnit, effect = write(0)) implements redirect ${ transferTile($1, $self, false) }
    }

    // --- Scala Backend
    impl (bram_new)   (codegen($cala, ${ Array.fill($size.toInt)($zero) })) // $t[T] refers to concrete type in IR
    impl (bram_load)  (codegen($cala, ${ $bram.apply($addr.toInt) }))
    impl (bram_store) (codegen($cala, ${ $bram.update($addr.toInt, $value) }))
    impl (bram_reset) (codegen($cala, ${ (0 until $bram.length).foreach{i => $bram.update(i, $zero) }}))

    // --- Dot Backend
    impl (bram_new)   (codegen(dot, ${
      @ if (isDblBuf(sym)) {
      	$sym [margin=0 rankdir="LR" label="{<st> | <ld>}" xlabel="$sym "
              shape="record" color=$dblbufBorderColor  style="filled"
              fillcolor=$bramFillColor ]
      @ } else {
        	$sym [label="$sym " shape="square" style="filled" fillcolor=$bramFillColor ]
      @ }
		}))
		impl (bram_load)  (codegen(dot, ${
			$addr -> $bram [ headlabel="addr" ]
			//$sym [style="invisible" height=0 size=0 margin=0 label=""]
			//$sym [label=$sym fillcolor="lightgray" style="filled"]
			@ emitValDef(sym, bram)
		}))
		impl (bram_store) (codegen(dot, ${
			$addr -> $bram [ headlabel="addr" ]
			$value -> $bram [ headlabel="data" ]
		}))
    //impl (bram_reset) (codegen(dot, ${ }))

    // --- MaxJ Backend
    // bram_new (extern)
    // bram_load (extern)
    // bram_store (extern)
    // impl (bram_reset) (codegen(maxj, ${ })) TODO: removed from dhdl?

  }
  // TODO: Size of offchip memory can be a staged value, but it can't be a value which is calculated in hardware
  //       Any way to make this distinction?
  // TODO: Change interface of tile load / store to words rather than BRAMs?
  def importOffChip() {
    val T = tpePar("T")

    val OffChip = lookupTpe("OffChipMem")
    val Tile    = lookupTpe("Tile")
    val BRAM    = lookupTpe("BRAM")
    val Range   = lookupTpe("Range")
    val Idx     = lookupAlias("Index")

    // --- Nodes
    val offchip_new   = internal (OffChip) ("offchip_new", T, ("size", Idx) :: OffChip(T), effect = mutable)
    val offchip_load  = internal (OffChip) ("offchip_load", T, (OffChip(T), Idx, Idx) :: Vector(T))
    val offchip_store = internal (OffChip) ("offchip_store", T, (OffChip(T), Idx, Vector(T)) :: MUnit, effect = write(0))

    // --- Internals
    internal (OffChip) ("offchip_create", T, (SOption(SString), SList(Idx)) :: OffChip(T)) implements composite ${
      if ($1.length < 1) stageError("Cannot create an OffChipMem with zero dimensions")
      val offchip = offchip_new[T](productTree($1))
      $0.foreach{name => nameOf(offchip) = name }
      dimsOf(offchip) = $1
      offchip
    }

    // --- API
    /** Creates a reference to a multi-dimensional array in main memory with given name and dimensions
     * @param name
     * @param dims
     **/
    static (OffChip) ("apply", T, (SString, varArgs(Idx)) :: OffChip(T), TNum(T)) implements composite ${ offchip_create(Some($0), $1.toList) }
    /** Creates a reference to an unnamed multi-dimensional array in main memory with given dimensions
     * @param dims
     **/
    static (OffChip) ("apply", T, (varArgs(Idx)) :: OffChip(T), TNum(T)) implements composite ${ offchip_create(None, $0.toList) }

    // Offer multiple versions of tile select since implicit cast from signed int to range isn't working
    val OffChip_API = withTpe(OffChip)
    OffChip_API {
      /** Loads a fixed size tile of this 1D OffChipMem onto an on-chip Vector.
       * @param cols
       **/
      infix ("load") (Range :: Vector(T)) implements composite ${ offchip_load($self, List(0.as[Index]), $1) }

      /** Loads a fixed size 1D column tile of this 2D OffChipMem onto an on-chip Vector.
       * @param row
       * @param cols
       **/
      infix ("load") ((Idx, Range) :: Vector(T)) implements composite ${ offchip_load($self, List($1, 0.as[Index]), $2) }

      /** Loads a fixed size 1D page tile of this 3D OffChipMem onto an on-chip Vector.
       * @param row
       * @param col
       * @param pages
       **/
      infix ("load") ((Idx,Idx,Range) :: Vector(T)) implements composite ${ offchip_load($self, List($1, $2, 0.as[Index]), $3) }


      /** Creates a reference to a 1D Tile of this 1D OffChipMem which can be loaded into on-chip BRAM.
       * @param cols
       **/
      infix ("apply") (Range :: Tile(T)) implements composite ${ tile_create($self, List($1)) }
      /** Creates a reference to a 2D Tile of this 2D OffChipMem which can be loaded into on-chip BRAM.
       * @param rows
       * @param cols
       **/
      infix ("apply") ((Range,Range) :: Tile(T)) implements composite ${ tile_create($self, List($1,$2)) }
      /** Creates a reference to a 3D Tile of this 3D OffChipMem which can be loaded into on-chip BRAM.
       * @param rows
       * @param cols
       * @param pages
       **/
      infix ("apply") ((Range,Range,Range) :: Tile(T)) implements composite ${ tile_create($self, List($1,$2,$3)) }

      // Hack version for adding explicit parallelization factors to a tile load / store
      /** @nodoc - syntax needs some tweaking here **/
      infix ("apply") ((Range,MInt) :: Tile(T)) implements composite ${
        val tile = tile_create($self, List($1))
        tilePar(tile) = $2
        tile
      }
      /** @nodoc - syntax needs some tweaking here **/
      infix ("apply") ((Range,Range,MInt) :: Tile(T)) implements composite ${
        val tile = tile_create($self, List($1,$2))
        tilePar(tile) = $3
        tile
      }
      /** @nodoc - syntax needs some tweaking here **/
      infix ("apply") ((Range,Range,Range,MInt) :: Tile(T)) implements composite ${
        val tile = tile_create($self, List($1,$2,$3))
        tilePar(tile) = $4
        tile
      }

      // 2D -> 1D
      /** Creates a reference to a 1D row Tile of this 2D OffChipMem
       * @param row
       * @param cols
       **/
      infix ("apply") ((Idx, Range) :: Tile(T)) implements composite ${ tile_create($self, List(unitRange($1), $2)) }
      /** Creates a reference to a 1D column Tile of this 2D OffChipMem
       * @param rows
       * @param col
       **/
      infix ("apply") ((Range, Idx) :: Tile(T)) implements composite ${ tile_create($self, List($1, unitRange($2))) }

      // 3D -> 2D
      /** Creates a reference to a 2D column/page Tile of this 3D OffChipMem
       * @param row
       * @param cols
       * @param pages
       **/
      infix ("apply") ((Idx, Range, Range) :: Tile(T)) implements composite ${ tile_create($self, List(unitRange($1), $2, $3)) }
      /** Creates a reference to a 2D row/page Tile of this 3D OffChipMem
       * @param rows
       * @param col
       * @param pages
       **/
      infix ("apply") ((Range, Idx, Range) :: Tile(T)) implements composite ${ tile_create($self, List($1, unitRange($2), $3)) }
      /** Creates a reference to a 2D row/column Tile of this 3D OffChipMem
       * @param rows
       * @param cols
       * @param page
       **/
      infix ("apply") ((Range, Range, Idx) :: Tile(T)) implements composite ${ tile_create($self, List($1, $2, unitRange($3))) }

      // 3D -> 1D
      /** Creates a reference to a 1D page Tile of this 3D OffChipMem
       * @param row
       * @param col
       * @param pages
       **/
      infix ("apply") ((Idx, Idx, Range) :: Tile(T)) implements composite ${ tile_create($self, List(unitRange($1), unitRange($2), $3)) }
      /** Creates a reference to a 1D column Tile of this 3D OffChipMem
       * @param row
       * @param cols
       * @param page
       **/
      infix ("apply") ((Idx, Range, Idx) :: Tile(T)) implements composite ${ tile_create($self, List(unitRange($1), $2, unitRange($3))) }
      /** Creates a reference to a 1D row Tile of this 3D OffChipMem
       * @param rows
       * @param col
       * @param page
       **/
      infix ("apply") ((Range, Idx, Idx) :: Tile(T)) implements composite ${ tile_create($self, List($1, unitRange($2), unitRange($3))) }
    }

    // --- Scala Backend
    impl (offchip_new) (codegen($cala, ${ new Array[$t[T]]($size.toInt) }))

    val lbl = "\"\\\"\" + quote(sym)"
    val endlbl = "\"\\\"\""

		// --- Dot Backend
		impl (offchip_new) (codegen(dot, ${
			@ alwaysGen {
				@ var label = \$lbl
				@ if (quote(size).forall(_.isDigit)) {
					@ 	label += ", size=" + quote(size)
				@ } else {
			  	$size -> $sym [ headlabel="size" ]
				@ }
				@ label += \$endlbl
        $sym [ label=$label shape="square" fontcolor="white" color="white" style="filled"
			  fillcolor=$dramFillColor color=black]
      @ }
		}))

		// --- MaxJ Backend
		//offchip_new (extern)
  }


  def importTiles() {
    val T = tpePar("T")

    val Tile    = lookupTpe("Tile")
    val OffChip = lookupTpe("OffChipMem")
    val BRAM    = lookupTpe("BRAM")
    val Range   = lookupTpe("Range")

    // TODO: How to avoid CSE? Doesn't matter except that same symbol may be returned
    // and need different symbols to manage offset staging metadata properly
    data(Tile, ("_target", OffChip(T)))
    internal (Tile) ("tile_new", T, OffChip(T) :: Tile(T)) implements allocates(Tile, ${$0})
    internal (Tile) ("tile_create", T, (OffChip(T), SList(Range)) :: Tile(T)) implements composite ${
      if (dimsOf($0).length != $1.length) stageError("Attempting to access " + dimsOf($0).length + "D memory with " + $1.length + " indices")
      val tile = tile_new($0)
      rangesOf(tile) = $1
      tile
    }
    internal.infix (Tile) ("mem", T, Tile(T) :: OffChip(T)) implements getter(0, "_target")


    /** Creates a store from the given on-chip BRAM to this Tile of off-chip memory
     * @param bram
     **/
    infix (Tile) (":=", T, (Tile(T), BRAM(T)) :: MUnit, effect = write(0)) implements redirect ${ transferTile($0, $1, true) }

    /** @nodoc - not actually a user-facing method for now **/
    direct (Tile) ("transferTile", T, (("tile",Tile(T)), ("local",BRAM(T)), ("store", SBoolean)) :: MUnit, effect = simple) implements composite ${
      val mem      = $tile.mem
      val ranges   = rangesOf($tile)
      val offsets  = ranges.map(_.start)
      val unitDims = ranges.map(isUnit(_))
      val tileDims = ranges.map(_.len)

      val cmdCtrs = if (tileDims.length > 1) tileDims.take(tileDims.length - 1).map{d => Counter(max = d) } else Counter(max = 1.as[Index])

      Pipe(CounterChain(cmdCtrs:_*)){indices =>
        val inds = indices.toList :+ 0.as[Index]
        val indsDropUnits = inds.zip(unitDims).filter{case (i,isUnitDim) => !isUnitDim}

        val memOfs = calcAddress(offsets.zip(inds).map{case (a,b) => a + b}, dimsOf(mem))
        val localOfs = calcAddress(indsDropUnits, dimsOf(local))

        if ($store) {
          val vec = bram_load_vector(local, localOfs, tileDims.last)
          offchip_store(mem, memOfs, vec, tilePar($tile).getOrElse(param(1)))
        }
        else {
          val vec = offchip_load(mem, memOfs, tileDims.last)  // Load vector from offchip
          bram_store_vector(local, localOfs, vec, tilePar($tile).getOrElse(param(1)))
        }
      }
    }

	}
}