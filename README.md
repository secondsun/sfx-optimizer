# sfx-optimizer

A compiler pass, register allocator, and interactive visualization tool for SNES SuperFX (GSU-1 and GSU-2) assembly code written with [ca65](https://cc65.github.io/).
It targets projects adhering to `X-GSU` and `libSFX` conventions.

---

## Features

- **Control Flow Graph (CFG) Construction**: Parses ca65 assembly and pseudo-macros into structured basic blocks (`CodeBlock`, `CallBlock`, `FunctionStart`, etc.).
- **Liveness Analysis**: Calculates precise live ranges (start/end lines, read/write access points) for physical hardware registers and pseudo-variables.
- **Graph-Coloring Register Allocation**: Implements Chaitin-Briggs / Kempe heuristic graph coloring to map pseudo-variables to hardware SuperFX registers (`R1`–`R9`, `R11`–`R13`), handling fixed registers, pre-colored constraints, and stack spills.
- **Strict Function Scoping**: Isolates live ranges and register allocation across function boundaries.
- **Interactive Visual Code Viewer**: A web-based inspector to explore assembly sources, liveness swimlanes, instruction effects, and register allocation.

---

## Interactive Code Viewer

The optimizer includes a built-in web viewer for inspecting variable liveness, register allocations, and instruction dependencies in real time.

![SFX Optimizer Code Viewer Screenshot](src/main/resources/viewer/index.html) <!-- UI entry point -->

### Key Viewer Features
- **Liveness Swimlanes**: Visual horizontal spans representing the live ranges of physical registers and declared variables.
- **Read / Write Indicators**: Clear `R` (read) and `W` (write) badges on spans and source tokens.
- **Interactive Connections**: Hovering over any register or swimlane dynamically renders bezier curves connecting the span to every in-code usage.
- **Allocation View**: Displays the allocated hardware register (or `SPILL`) and the interference set (conflicting variables) for each variable.
- **Function Boundary Partitions**: Swimlanes terminate strictly at `function` / `endfunction` boundaries so scopes never bleed across routines.
- **Implicit Register Tracking**: Accurately tracks hidden SuperFX register side effects (e.g., `ldw`/`stw` accessing `R0`, `lmult`/`fmult` reading `R6` and writing `R4`, `merge` reading `R7`/`R8`, `romb` reading `R14`).
- **Pinnable Popovers**: Click any swimlane to pin a detailed inspection card showing start/end lines, access points, assigned registers, and interference conflicts. Popovers stay anchored within view and scroll with their corresponding span.
- **File Browser**: Easily browse test fixtures in `src/test/resources/code_blocks/` and real-world routines in `src/test/resources/homebrew/`.

### Starting the Viewer

Start the viewer server using Maven:
```bash
./mvnw test -Dtest=ViewerServerTest#launchViewerManually
```
Or run `dev.secondsun.sfxoptimizer.viewer.ViewerServerKt`:
```bash
java -jar target/sfx-optimizer-0.3.jar
```
Then open your browser to `http://localhost:8080`.

---

## SuperFX (GSU) Architecture & Conventions

1. **Hardware Registers**:
   - **`R0`**: Accumulator and default operand for arithmetic (`add`, `sub`, `and`, `or`, etc.) and memory loads/stores (`ldw`, `stw`).
   - **`R1` - `R9`**: General-purpose registers available for variable allocation.
   - **`R10`**: **Stack Pointer**. Dedicated to the call stack convention (`r10 stack`). *Never allocated to general variables.*
   - **`R11` - `R13`**: General-purpose / link registers. Available for allocation when not reserved by specific subroutines.
   - **`R14`**: ROM table pointer.
   - **`R15`**: **Program Counter (PC)**. Writing to R15 executes a branch/jump. *Never allocated to variables.*

2. **Modal Prefix Instructions (`with`, `to`, `from`)**:
   - `to Rn`: Directs the result of the *next* instruction to `Rn`.
   - `from Rn`: Directs the secondary operand of the *next* instruction to `Rn`.
   - `with Rn`: Simultaneously directs both destination and secondary operand to `Rn`.

3. **Branch Delay Slots**:
   - Branch instructions (`bra`, `beq`, `bne`, `bmi`, etc.) have a 1-instruction delay slot executed immediately after the branch.

---

## Pseudo-Macros & Syntax

The optimizer recognizes high-level pseudo-macros in ca65 assembly:

### 1. `register`
Declares one or more variable labels to be backed by registers:
```assembly
register input, output
```
To force a variable to a specific hardware register (pre-colored constraint):
```assembly
register output = r4
```
> **Note**: Only identifiers declared with the `register` keyword (or function parameters) are tracked in swimlanes and allocated registers. Standard code labels (jump targets, branch labels, address constants) are excluded.

### 2. `function` / `endfunction`
Defines a subroutine boundary and optional parameter variables:
```assembly
function vector3_copy source, dest
    register temp
    ...
    return dest
endfunction
```
Functions enforce independent register scopes. Live ranges and interference graphs never cross function boundaries.

### 3. `call`
Invokes a subroutine with parameter validation:
```assembly
call vector3_copy in_vec, out_vec
```

### 4. `with`, `to`, `from`
Applied to declared pseudo-variables or physical registers to manage SuperFX prefix operations.

### 5. `for` / `forr`
SuperFX hardware loop constructs (`for <count>` ... `endfor`, `forr <reg>` ... `endfor`).

---

## Project Structure

```
sfx-optimizer/
├── src/main/kotlin/dev/secondsun/sfxoptimizer/
│   ├── Allocator.kt            # Graph coloring register allocation (Chaitin-Briggs)
│   ├── Interval.kt             # Variable liveness intervals and access points
│   ├── Parser.kt               # Hardware register definitions and utilities
│   ├── graphbuilder/
│   │   ├── CA65Grapher.kt      # Token parser & CFG builder
│   │   └── RegisterLabel.kt    # Register label metadata
│   ├── graphnode/
│   │   └── CodeGraph.kt        # CFG nodes (Start, CodeBlock, CallBlock, etc.)
│   └── viewer/
│       ├── CodeAnalyzer.kt     # Liveness, instruction effect, & scope analyzer
│       ├── ViewerDto.kt        # JSON DTOs for the web client
│       └── ViewerServer.kt     # Embedded HTTP server for the viewer
├── src/main/resources/viewer/  # HTML, CSS, and JS web viewer assets
└── src/test/resources/
    ├── code_blocks/            # Sample .sgs snippets for testing & viewing
    └── homebrew/               # X-GSU and libSFX assembly test suites
```

---

## Build, Test, and Verification

Always use the included Maven wrapper (`./mvnw`):

- **Run all tests**:
  ```bash
  ./mvnw test
  ```
- **Run a single test**:
  ```bash
  ./mvnw test -Dtest=ViewerServerTest#test\ forced\ register\ assignment\ precoloring
  ```
- **Check code formatting**:
  ```bash
  ./mvnw spotless:check
  ```
- **Apply code formatting**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Full verification (Clean, Tests, Javadoc, Spotless)**:
  ```bash
  ./mvnw clean verify -Dgpg.skip=true
  ```