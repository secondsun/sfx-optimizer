# 0001. Loop-Invariant Caller-Save Hoisting Requires a Read-Free, Write-Free Loop

Date: 2026-10-31

## Status

Accepted. Deviates from issue #19, "Loop-Invariant Caller-Save Hoisting".

## Context

Issue #19 specifies hoisting `gsu_stack_push`/`gsu_stack_pop` pairs around a loop when a caller-saved register is live across the loop and "has no definitions inside the loop body", which the user stories extend to "or only read (not defined)".

A hoisted save restores the register once, after the loop. The callee clobbers the register on every iteration, so any read of that register inside the loop after the first call sees a clobbered value. Hoisting a register that is read in the loop is therefore a miscompile.

## Decision

`SfxCompiler.findHoistLoop` hoists a register only when:

1. No interval mapped to that register has a read or write inside the loop (the register is only live *across* the loop).
2. The loop is a hardware `for`/`forR` ... `endfor` loop. `iwt r15` back-edge loops are excluded because the pop would land in the branch delay slot.
3. The loop contains no `return` and no branch to a label outside the loop.

The outermost loop meeting these conditions is chosen. Registers read inside the loop keep their per-iteration push/pop at the call site.

## Consequences

- Hoisting fires less often than the issue text implies. Loops that read a clobbered variable keep per-iteration stack traffic, which is the only correct lowering without register reassignment.
- A future pass can recover the read-in-loop case by restoring the register after each call from a single hoisted slot, or by coloring around the callee's clobber set (Caller-Save Avoidance).

