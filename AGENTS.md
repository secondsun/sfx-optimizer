# AGENTS.md — Agent & Contributor Guide for sfx-optimizer

## 1. Project Mission & Overview
`sfx-optimizer` is a compiler pass and register allocator for SNES SuperFX (GSU-1 and GSU-2) assembly code written with [ca65](https://cc65.github.io/).
It targets projects using `X-GSU` and `libSFX` conventions.

The optimizer takes assembly source files that use high-level register pseudo-macros (`register`, `function`, `call`, `with`/`to`/`from`), converts them into a Control Flow Graph (CFG), tracks register variable liveness/intervals, executes a **Graph-Coloring Register Allocation** pass, and lowers pseudo-macros to optimized hardware SuperFX instructions while minimizing stack spills.

---

## 2. Architecture & Data Flow Pipeline

```
[CA65 Source with Pseudo-Macros]
              │
              ▼ (dev.secondsun:retro-common)
       [CA65Scanner]  ──> TokenizedFile
              │
              ▼ (graphbuilder/CA65Grapher.kt)
        [CodeGraph]   ──> CFG of CodeNodes (Start, CodeBlock, CallBlock, FunctionStart, End)
              │
              ▼ (Interval.kt)
    [Liveness Analysis] ──> Variable live ranges & IntervalKeys
              │
              ▼ (Allocator.kt)
   [Interference Graph] ──> Chaitin-Briggs / Kempe Graph Coloring
              │
              ▼
   [Register Assignment] ──> Hardware SuperFX registers allocated (or Stack Spills)
              │
              ▼
   [Optimized CA65 Output]
```

### Key Modules & Files
- [`CA65Grapher.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/graphbuilder/CA65Grapher.kt): Parses tokens and constructs CFG blocks (`CodeBlock`, `CallBlock`, etc.).
- [`CodeGraph.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/graphnode/CodeGraph.kt): Directed graph representing the program flow, entry point, functions, and exits.
- [`Interval.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Interval.kt): Encapsulates live range start/end and read/write access points for register keys and labels.
- [`Allocator.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Allocator.kt): Houses register allocation, interference graph generation, graph coloring, and spill handling.
- [`Parser.kt`](file:///home/summers/Projects/sfx-optimizer/src/main/kotlin/dev/secondsun/sfxoptimizer/Parser.kt): Hardware register definitions (`Constants.Register`), register lookup, and parsing utilities.

---

## 3. SuperFX (GSU) Architecture & Calling Conventions (CRITICAL)

The SuperFX chip (GSU) has strict hardware register behavior that agents MUST respect:

1. **Register Map**:
   - **`R0`**: Primary accumulator and default parameter/return register. Instructions like `add`, `sub`, `and`, `or` use R0 implicitly if no source/dest is specified.
   - **`R1` - `R9`**: General-purpose registers available for variable allocation.
   - **`R10`**: **Stack Pointer**. Dedicated to the call stack convention in X-GSU (`r10 stack`). **DO NOT allocate R10 as a general variable.**
   - **`R11` - `R13`**: General-purpose registers / link registers. Available for allocation when not used by specific routines.
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

## 4. Build, Test, & Execution Commands

Always use the included Maven wrapper (`./mvnw`):

- **Compile and Test All**:
  ```bash
  ./mvnw test
  ```
- **Run Single Test Class**:
  ```bash
  ./mvnw test -Dtest=CodeBlockTests
  ```
- **Run Single Test Method**:
  ```bash
  ./mvnw test -Dtest=CodeBlockTests#does_a_trivial_code_block_generate_a_start__end__and_code
  ```
- **Clean Package (Create JAR)**:
  ```bash
  ./mvnw clean package
  ```
- **Check Code Formatting (Spotless / ktlint)**:
  ```bash
  ./mvnw spotless:check
  ```
- **Apply Code Formatting (Spotless / ktlint)**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Full Verification (Tests, Javadoc, Spotless, same as CI)**:
  ```bash
  ./mvnw clean verify -Dgpg.skip=true
  ```

---

## 5. Coding Standards & Agent Guidelines

1. **Kotlin Idioms (Kotlin 2.4+ / Java 26)**:
   - Use `sealed interface` or `sealed class` for algebraic data types (`AllocationResult`, `CodeNode`, `IntervalKey`).
   - Use `data object` for singleton states in sealed hierarchies.
   - Prefer immutable collections (`List`, `Set`) except for internal allocation structures.
   - Use `Register.entries` rather than `Register.values()`.
   - Avoid wildcard imports (`import foo.*`); ktlint and Spotless will reject them. Keep formatting clean with `./mvnw spotless:apply`.

2. **Testing Guardrails**:
   - Maintain test fixtures in `src/test/resources/homebrew/` (`X-GSU` and `libSFX`). Do not modify these assembly files unless writing tests specifically targeting new syntax.
   - All tests live in `dev.secondsun.sfxoptimizer` matching the package structure.
   - Follow Test-Driven Development (TDD): write or un-ignore test cases before expanding `Allocator.kt`.

3. **Register Allocation Conventions**:
   - Use **Interference Graph Coloring** (Chaitin-Briggs / Kempe heuristic).
   - Variables that interfere (overlapping live ranges) cannot share the same hardware register.
   - Variables with pre-assigned registers (`register l1 = r1` or instructions with fixed registers like `mult`, `merge`) create pre-colored nodes in the interference graph.
