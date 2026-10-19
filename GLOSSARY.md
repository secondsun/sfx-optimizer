# SFX-Optimizer

Control flow analysis, live-range interval computation, Chaitin-Briggs graph-coloring register allocation, and X-GSU macro lowering for SuperFX assembly.

## Language

**Instruction Effect**:
The per-line record of explicit and implicit hardware register and pseudo-variable reads, writes, and memory-address operands produced after evaluating `with`, `to`, and `from` modal prefixes.
_Avoid_: Line liveness, opcode usage, token effect

**Live-Range Interval**:
The contiguous span of source lines from the first write or loop-header entry to the final read or loop-back-edge exit during which a hardware register or pseudo-variable holds a live value.
_Avoid_: Variable lifetime, liveness span, usage range

**Interference Graph**:
The undirected graph in which nodes represent hardware registers and pseudo-variables and edges connect any two nodes whose live-range intervals overlap.
_Avoid_: Conflict graph, overlap matrix

**Address Register Pool**:
The subset of SuperFX general-purpose registers (`R1`–`R9`, `R11`) legal as indirect memory address operands `(Rm)` in `ldw`, `stw`, `ldb`, and `stb` instructions.
_Avoid_: Primary pool, pointer registers

**Extended Register Pool**:
The broader set of allocatable SuperFX registers combining the Address Register Pool with `R12` and `R13` for scalar variables that are never used as indirect memory addresses or inside hardware `loop` bodies.
_Avoid_: Secondary pool, full register pool

**Caller-Save Avoidance**:
The interprocedural preference during graph coloring that steers variables live across a `call` site away from registers clobbered by the callee, avoiding stack push/pop spill overhead around the call.
_Avoid_: Call clobber penalty, callee interference

