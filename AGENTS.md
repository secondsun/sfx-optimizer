# AGENTS.md — Agent & Contributor Guide for `sfx-optimizer`

## 1. Mission & Pipeline

`sfx-optimizer` is the SuperFX (GSU / FX3) compiler pass, Chaitin-Briggs register allocator, and interactive liveness viewer for `ca65` / `X-GSU` assembly. For full integration architecture, CLI demos, and A/B ROM/cycle benchmarks, see [`../docs/sfx-optimizer-pipeline-integration.md`](../docs/sfx-optimizer-pipeline-integration.md).

```
  [CA65 Source + .include] ──> CA65IncludeExpander (SourceOrigin + depfiles)
             │
             ▼ SfxInliner (512B cache-budget guard + >=8-var pressure guard)
             ▼ SfxLoopInvariantHoister
             ▼ SfxCfgBuilder + SfxLivenessAnalyzer + SfxProgramAnalyzer (ScopeCfg)
             ├──> Allocator (2-pass Chaitin-Briggs + R10 spill scratch reservation)
             │       ▼ SfxCompiler (symbolic regs, R10 spill frames, .dbg file/line)
             │       ▼ SfxPeepholeOptimizer (.dbg-transparent, iwt->ibt, r0, ldw+move)
             │       ▼ [.opt.sgs + .d depfile]
             └──> viewer/CodeAnalyzer + ViewerServer (port 8080)
```

### Key Modules
- [`SfxCfgBuilder.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/analysis/SfxCfgBuilder.kt): Per-scope CFGs (`ScopeCfg`, `CfgBlock`, `NaturalLoop`) with delay-slot and natural-loop extraction.
- [`SfxProgramAnalyzer.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/analysis/SfxProgramAnalyzer.kt) & [`SfxLivenessAnalyzer.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/analysis/SfxLivenessAnalyzer.kt): Bottom-up scope orchestration, effect modeling, `explicitlyUsedKeys` filtering, 2-pass `allocateWithSpillScratch`.
- [`Allocator.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/Allocator.kt) & [`Interval.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/Interval.kt): Interference graph, Kempe graph coloring (`preferKeepKeys`), pre-colored constraints.
- [`SfxCompiler.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/compiler/SfxCompiler.kt), [`SfxInliner.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/compiler/SfxInliner.kt), [`SfxPeepholeOptimizer.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/compiler/SfxPeepholeOptimizer.kt): Cache/pressure-aware inlining, parallel-move call lowering, balanced `R10` stack spill frames with `ScopeCfg` `deltaSp` tracking, `.dbg file`/`.dbg line` emission, and SuperFX peephole passes.
- [`CA65MacroExpander.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/macro/CA65MacroExpander.kt) & [`StackDepthTracker.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/stack/StackDepthTracker.kt): Source-mapped macro expansion (`SourceOrigin`) and `R10` stack displacement verification.
- [`Main.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/viewer/Main.kt), [`CodeAnalyzer.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/viewer/CodeAnalyzer.kt), [`ViewerServer.kt`](src/main/kotlin/dev/secondsun/sfxoptimizer/viewer/ViewerServer.kt): CLI entry point (`--compile`, `--depfile`, `--Werror`, `--stats`) and interactive web viewer (`src/main/resources/viewer/`).

---

## 2. SuperFX Hardware Rules & Allocator Invariants

- **SuperFX (FX3) Hardware Register Roles & Constraints**: Authoritative single source of truth is [`../.agents/rules/sfx-assembly-rules.md`](../.agents/rules/sfx-assembly-rules.md) (`R0` default accumulator, `R10` ascending word stack pointer — never allocate, `R15` PC — never allocate, FX3 has no hardware `MERGE`, 4-bit immediate `#0..#15` arithmetic limit, 1-instruction modal prefix lifetime, 1-instruction branch delay slot).
- **Declared Variables Only**: Only identifiers declared via `register` or function parameter/return lists are allocated; plain labels and constants are untouched.
- **Pre-coloring**: `register my_var = r4` locks `my_var` to `Register.R4` in the interference graph.
- **Strict Function Scoping**: Live ranges and interference graphs are isolated per `function ... endfunction` and `(main)`.
- **Spill Lowering Safety**: Pass 2 reserves 2 address-capable scratch registers (`R1..R9, R11`) only when an explicitly used variable spills; address computation precedes flag-setting instructions so post-op `from`/`stw` preserves `Z/S/CY/OV` for conditional branches; `R10` offsets adjust for dynamic `deltaSp` (`for`/`forR` `+4B`, `gsu_stack_push`/`pop`).

---

## 3. Build, Test & CLI Commands

Always use `./mvnw`:
- **Run Tests**: `./mvnw test` (or single test: `./mvnw test -Dtest=SfxPipelineIntegrationTest`)
- **Format Check / Apply**: `./mvnw spotless:check` / `./mvnw spotless:apply`
- **Full Verification (CI parity)**: `./mvnw clean verify -Dgpg.skip=true`
- **Repackage & Compile `.sgs` via CLI** (`target/sfx-optimizer.jar` is not rebuilt by `test`):
  ```bash
  ./mvnw -q package -DskipTests
  java -jar target/sfx-optimizer.jar -c <in>.sgs -o <out>.opt.sgs \
    --depfile <out>.d --dep-target <out>.ogs --Werror --stats \
    -I <dir-of-in> -I <libSFX/include>
  ```
  Additional pass flags: `--no-inline`, `--no-hoist`, `--no-peephole`, `--no-debug-lines`, `--inline-threshold <N>`.
  In `snes-sfx-demo`, override compiler flags via `make -C X-GSU/IsoEngine SFX_OPT_FLAGS="--Werror --stats"`.

---

## 4. Testing Conventions

- Test compiler behavior through the `SfxCompiler.compileSource` / `compileFile` seam (see [`../CODING_STANDARDS.md`](../CODING_STANDARDS.md)).
- Viewer-inspectable `.sgs` snippets live in `src/test/resources/code_blocks/`; treat `src/test/resources/homebrew/` as read-only upstream fixtures unless specifically testing new syntax.
- Never use `return@repeat` inside Kotlin `repeat(...)` blocks (it acts as `continue`, not `break` — enforced by pre-commit and CI).
