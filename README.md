This is an optimizer and register allocator for use with my X-GSU libs.

This is designed for the SuperFX chips

# Usage

sfx-optimize INPUT OUTPUT
 * INPUT : the "main file" where code execution will begin
 * OUTPUT : a directory where optimized files will live

# Theory of operation
This optimizer works on and outputs ca65 assembly source.

It knows about my coding convention used by my X-GSU libs such as the 
r10 stack, function calls, and loops.

It will consume optimizer pseudo-macros to declare variables.
Variables are scoped to their local function block, loop block, etc.
Global variables are not in scope.

The allocator will scan blocks to build a tree, and the optimizer will 
try to minimize stack pushes across scopes. It will also introduce 
to/from/with as appropriate.

# Example Usage

```
function param
  with param 
  add param
  return param
endfunction

Main : 
  var input, output
  iwt input, #5
  call output = myFuction param
  with output
  add #0
  beq Main
```

This will generate the ca65 code

```

function 
  to r3
  add r3
  return 
endfunction

Main : 
  iwt r0, #5
  call myFuction
  with r3
  add #0
  beq Main
```



# Pseudo-Macros
 * var - declare a variable 
 * function (#param )* - declare a function with parameters
 * return param - returns the value of param
 * call function (#var)* - call a function using declared vars
 * with/to/from var - maps to the superFX with/to/from but gets optimized and assigned. Must be used as r0 is never assumed