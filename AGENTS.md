# AGENTS.md — Agent & Contributor Guide for sfx-optimizer

## 1. Project Mission & Overview
`sfx-optimizer` is a compiler pass, register allocator, and interactive visualization tool for SNES SuperFX (GSU-1 and GSU-2) assembly code written with [ca65](https://cc65.github.io/).
It targets projects adhering to `X-GSU` and `libSFX` conventions.

The optimizer processes assembly source files containing high-level register pseudo-macros (`register`, `function`, `call`, `with`/`to`/`from`), builds a Control Flow Graph (CFG), tracks register variable liveness/intervals, executes a **Chaitin-Briggs Graph-Coloring Register Allocation** pass, and lowers pseudo-macros to optimized SuperFX instructions while minimizing stack spills. An integrated web-based viewer provides real-time graphical inspection of liveness swimlanes, allocations, and instruction effects.

---

## 2. Architecture & Data Flow Pipeline

```
              [CA65 Source with Pseudo-Macros]
                             │
                             ▼ (dev.secondsun:retro-common)
                      [CA65Scanner]  ──> TokenizedFile
                             │
                             ├─────────────────────────────────────────────────┐
                             │                                                 │
                             ▼ (graphbuilder/CA65Grapher.kt)                   ▼ (viewer/CodeAnalyzer.kt)
                       [CodeGraph]                                     [Liveness & Scopes]
                             │                                                 │
                             ▼ (Interval.kt)                                   ▼ (Allocator.kt)
                    [Liveness Analysis]                              [Interference & Coloring]
                             │                                                 │
                             ▼ (Allocator.kt)                                  ▼ (viewer/ViewerDto.kt)
                   [Interference Graph]                             [JSON DTO Serialization]
                             │                                                 │
                             ▼                                                 ▼ (viewer/ViewerServer.kt)
                   [Register Assignment]                            [Interactive Web UI]
                             │                                       (Swimlanes, Popovers, SVG Links)
                             ▼
                   [Optimized CA65 Output]
```

### Key Modules & Files
- [`CA65Grapher.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/graphbuilder/CA65Grapher.kt): Parses tokens and constructs CFG blocks (`CodeBlock`, `CallBlock`, `FunctionStart`, `End`).
- [`CodeGraph.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/graphnode/CodeGraph.kt): Directed graph representing program flow, entry point, functions, and exits.
- [`Interval.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Interval.kt): Encapsulates live range start/end and read/write access points for register keys and labels.
- [`Allocator.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Allocator.kt): Houses register allocation, interference graph generation, Chaitin-Briggs / Kempe graph coloring, pre-colored constraints, and spill handling.
- [`Parser.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Parser.kt): Hardware register definitions (`Constants.Register`), register lookup, and parsing utilities.
- [`CodeAnalyzer.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/viewer/CodeAnalyzer.kt): Analyzes assembly source for the viewer, calculating function boundaries, instruction effects (explicit and implicit), live ranges, interference sets, and register allocations.
- [`ViewerServer.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/viewer/ViewerServer.kt): Lightweight embedded HTTP server providing REST API endpoints (`/api/analyze`, `/api/files`, `/api/file`) and static web assets.
- [`src/main/resources/viewer/`](file:///home/summers/Projects/sfx-optimizer/src/main/resources/viewer/): Frontend assets (`index.html`, `viewer.css`, `viewer.js`) featuring interactive SVG connection curves, swimlanes, and pinned detail popovers.

---

## 3. SuperFX (GSU) Architecture & Calling Conventions (CRITICAL)

The SuperFX chip (GSU) has strict hardware register behavior that agents MUST respect:

1. **Register Map**:
   - **`R0`**: Primary accumulator and default parameter/return register. Arithmetic instructions like `add`, `sub`, `and`, `or` and memory operations (`ldw`, `stw`) use `R0` implicitly if no source/dest is specified.
   - **`R1` - `R9`**: General-purpose registers available for variable allocation.
   - **`R10`**: **Stack Pointer**. Dedicated to the call stack convention in X-GSU (`r10 stack`). **DO NOT allocate R10 as a general variable.**
   - **`R11` - `R13`**: General-purpose registers / link registers. Available for allocation when not reserved by specific subroutines.
   - **`R14`**: ROM table pointer / general register.
   - **`R15`**: **Program Counter (PC)**. Writing to R15 executes a branch/jump. **NEVER allocate R15.**

2. **Prefix Instructions (`with`, `to`, `from`)**:
   - SuperFX uses modal prefix instructions to redirect operand sources and destinations:
     - `to Rn`: Directs the result of the *next* instruction to `Rn`.
     - `from Rn`: Uses `Rn` as the secondary operand for the *next* instruction.
     - `with Rn`: Simultaneously sets `to Rn` and `from Rn`.

3. **Branch Delay Slots**:
   - Branch instructions (`bra`, `beq`, `bne`, `bmi`, etc.) have a 1-instruction delay slot that is executed immediately following the jump.

---

## 4. Register Allocation Semantics & Critical Assumptions

When modifying the compiler, allocator, or analyzer, preserve these core assumptions:

1. **Declared Variables Only**:
   - Only identifiers declared via the `register` pseudo-macro (e.g. `register var1, var2`) or listed as function parameters (e.g. `function foo p1, p2`) are tracked in liveness swimlanes and allocated hardware registers.
   - Standard assembly labels (e.g. branch targets `loop:`, `skip:`, data definitions, or address constants like `VECTOR_CROSS_OUT`) MUST NOT be treated as register variables.

2. **Forced Register Assignments (Pre-coloring)**:
   - Syntax: `register my_var = r4`.
   - Forced assignments specify an explicit hardware register requirement.
   - The token stream parses `=` via `TokenType.TOK_EQ`.
   - The allocator adds the variable to the pre-colored map (`precolored[IntervalKey.LabelKey("my_var")] = Register.R4`). Pre-colored nodes lock the assigned color and eliminate it from neighboring candidates during Kempe simplification.

3. **Strict Function Scoping**:
   - Each `function` ... `endfunction` block defines an isolated scope.
   - Live ranges and interference graphs MUST NOT bleed across function boundaries or into top-level code.
   - Top-level non-function code is grouped into its own isolated `(main)` scope.

4. **Implicit Register Side Effects**:
   - SuperFX instructions with implicit register dependencies MUST be modeled for accurate liveness analysis:
     - `ldw (Rn)`: Reads address in `Rn`, writes data to `R0`.
     - `stw (Rn)`: Reads address in `Rn`, reads data from `R0`.
     - `lmult` / `fmult`: Reads multiplicand from `R6`, writes low 16 bits of product to `R4` (and top bits to destination or `R0`).
     - `merge`: Reads `R7` and `R8`, merges their contents into destination or `R0`.
     - `romb`: Reads table offset from `R14`.
     - `getc`, `getb`, `getbl`, `getbh`, `getbs`: Read from ROM/RAM buffer pointers.

---

## 5. Build, Test, & Execution Commands

Always use the included Maven wrapper (`./mvnw`):

- **Compile and Test All**:
  ```bash
  ./mvnw test
  ```
- **Run Single Test Class**:
  ```bash
  ./mvnw test -Dtest=ViewerServerTest
  ```
- **Run Single Test Method**:
  ```bash
  ./mvnw test -Dtest=ViewerServerTest#test\ forced\ register\ assignment\ precoloring
  ```
- **Check Code Formatting (Spotless / ktlint)**:
  ```bash
  ./mvnw spotless:check
  ```
- **Apply Code Formatting (Spotless / ktlint)**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Launch Interactive Code Viewer**:
  ```bash
  ./mvnw test -Dtest=ViewerServerTest#launchViewerManually
  ```
  *Or run `ViewerServerKt.main()` directly.*
- **Full Verification (Tests, Javadoc, Spotless, same as CI)**:
  ```bash
  ./mvnw clean verify -Dgpg.skip=true
  ```

---

## 6. Coding Standards & Contributor Guidelines

1. **Kotlin Idioms (Kotlin 2.4+ / Java 26)**:
   - Use `sealed interface` or `sealed class` for algebraic data types (`AllocationResult`, `CodeNode`, `IntervalKey`).
   - Use `data object` for singleton states in sealed hierarchies.
   - Prefer immutable collections (`List`, `Set`, `Map`) except for internal allocation and graph structures.
   - Use `Register.entries` rather than `Register.values()`.

2. **Testing Guardrails**:
   - Code block test snippets live in `src/test/resources/code_blocks/` as `.sgs` files. When adding new syntax features or test cases in `CodeBlockTests.kt`, add corresponding test snippets in this directory so they can be inspected in the viewer.
   - Upstream test fixtures live in `src/test/resources/homebrew/` (`X-GSU` and `libSFX`). Do not modify these assembly files unless writing tests specifically targeting new syntax.
   - All tests live in `dev.secondsun.sfxoptimizer` matching the package structure.
   - Follow Test-Driven Development (TDD): write or un-ignore test cases before expanding `Allocator.kt` or `CodeAnalyzer.kt`.
