# C++ Op Implementation Debugging Task

## Context
You are debugging ND4J/SameDiff C++ operation implementations in a deep learning framework. The codebase is located in the `libnd4j` directory of the kompile repository. You need to systematically review 30 operations for memory safety issues, particularly view deletion bugs.

## Your Mission
Review all operations listed in `ops.txt` and identify/fix memory management bugs, with special focus on preventing view deletion crashes.

## Critical Background: The View Problem

### What is a View?
A **view** is an NDArray that shares its underlying data buffer with another array. Views are created by operations like:
- `reshape()` - may return view if memory layout allows
- `permute()` - always returns view (reordered axes)
- `slice()` / `strided_slice()` - always returns view (subset of data)
- `expand_dims()` - may return view (shape changed, data shared)

### Why Views are Dangerous
```cpp
// CATASTROPHIC BUG EXAMPLE:
auto* input = getInputArray();      // Original array
auto* permuted = input->permute({1, 0});  // Creates VIEW sharing buffer
delete permuted;  // CRASHES! Just deleted the shared buffer
// Now 'input' points to freed memory - use-after-free bug
```

### The Golden Rule
**NEVER DELETE A VIEW** - The parent array owns the buffer and will clean it up.

```cpp
// SAFE PATTERN:
auto* result = someOperation();
if (!result->isView()) {
    delete result;  // Only safe if NOT a view
}
// Better: Let workspace or caller handle cleanup
```

## Your Task Breakdown

### Step 1: Setup
1. Read the full debugging methodology from `DEBUG_OPS_PROMPT.md`
2. Load the operation list from `ops.txt` (30 operations total)
3. Familiarize yourself with the libnd4j codebase structure

### Step 2: For Each Operation in ops.txt

Follow this systematic process:

#### A. Locate the Implementation
Search for the operation in these locations (in order):
1. `libnd4j/include/ops/declarable/generic/<op_name>.cpp`
2. `libnd4j/include/ops/declarable/generic/parity_ops.cpp` (for common ops)
3. `libnd4j/include/ops/declarable/generic/transforms.cpp` (for transform ops)
4. `libnd4j/include/ops/declarable/generic/nn/` (for layer_norm, softmax)
5. `libnd4j/include/ops/declarable/generic/linalg/` (for matmul, tensordot)
6. Search recursively: `grep -r "OP_IMPL(<op_name>" libnd4j/`

#### B. Analyze Memory Flow
For the main execution method, trace:
1. **Inputs**: How many input arrays? Are they modified?
2. **Outputs**: Pre-allocated or newly created?
3. **Temporaries**: Any intermediate arrays allocated?
4. **Helpers**: Does it call helper classes that allocate memory?

#### C. Identify View Operations
Search the code for these patterns:
```cpp
->reshape(
->permute(
->slice(
->strided_slice(
->expand_dims(
->asT<
->dup(     // This is SAFE - creates new array
```

For each found, document:
- Line number
- Whether result is stored and later deleted
- Whether there's an `isView()` check before deletion

#### D. Check for Bugs
Look for these specific bug patterns:

**BUG PATTERN 1: Direct View Deletion**
```cpp
// SEARCH FOR:
auto* x = something->reshape(...);
// ... later ...
delete x;  // BUG if x is a view!
```

**BUG PATTERN 2: Workspace Confusion**
```cpp
// SEARCH FOR:
auto* temp = new NDArray(...);  // Manual allocation
// ... used in workspace scope ...
// Missing delete or double-free risk
```

**BUG PATTERN 3: Early Return Leak**
```cpp
// SEARCH FOR:
auto* allocated = new NDArray(...);
if (errorCondition) {
    return Status::ERROR;  // LEAK! Never deleted
}
delete allocated;
```

**BUG PATTERN 4: Output Overwrite**
```cpp
// SEARCH FOR:
auto* output = OUTPUT_VARIABLE(0);  // Framework-owned
// ... later ...
delete output;  // BUG! Don't delete framework arrays
```

**BUG PATTERN 5: Buffer Aliasing**
```cpp
// SEARCH FOR:
auto* a = INPUT_VARIABLE(0);
auto* b = a->reshape(...);  // Might be view
a->assign(newData);  // Might corrupt b if it's a view
```

#### E. Document Findings
For each operation, create a report using this template:

```markdown
## Operation: <OP_NAME>

**File**: `libnd4j/include/ops/declarable/.../file.cpp:LINE`
**Status**: ✅ SAFE | ⚠️ NEEDS_REVIEW | ❌ BUG_FOUND

### Memory Analysis
- Input arrays: <count>
- Output arrays: <count> (<pre-allocated/new>)
- Temporary allocations: <count>
- Workspace usage: YES/NO

### View Operations Detected
1. Line XXX: `result = input->reshape(...)` - [SAFE/UNSAFE]
2. Line YYY: `permuted = array->permute(...)` - [SAFE/UNSAFE]

### Bugs Found
- [ ] View deletion bug
- [ ] Memory leak
- [ ] Double-free risk
- [ ] Uninitialized output
- [ ] Buffer aliasing issue

### Issues:
1. **[CRITICAL/HIGH/MEDIUM/LOW]** Description of issue
   - Location: file.cpp:LINE
   - Impact: What will happen
   - Fix: What needs to change

### Recommended Fix
```cpp
// Before (buggy):
<buggy code>

// After (fixed):
<fixed code>
```

### Verification Needed
- [ ] Unit test exists
- [ ] AddressSanitizer check
- [ ] Valgrind check
- [ ] Stress test (1000+ iterations)
```

#### F. Priority Order
Review operations in this order (from DEBUG_OPS_PROMPT.md):

**Phase 1 - HIGH RISK (do these first):**
1. layer_norm
2. softmax
3. matmul
4. tensordot
5. gather
6. concat
7. stack

**Phase 2 - VIEW CRITICAL:**
8. reshape
9. permute
10. strided_slice
11. expand_dims

**Phase 3 - MEDIUM RISK:**
12. reduce_prod
13. xw_plus_b
14. Where

**Phase 4 - LOW RISK:**
15-30. Remaining ops (add, multiply, divide, sqrt, tanh, erf, cast, assign, equals, create, shape_of, size_at, rank, range, set_scalar, floordiv)

### Step 3: Generate Fixes

For each bug found, provide:
1. **Exact file and line number**
2. **Before code** (current buggy implementation)
3. **After code** (fixed implementation)
4. **Explanation** of why this fixes the issue
5. **Test case** to verify the fix

### Step 4: Create Summary Report

After reviewing all 30 ops, create `OP_REVIEW_SUMMARY.md` with:

```markdown
# ND4J Op Review Summary

**Date**: <date>
**Ops Reviewed**: 30/30
**Bugs Found**: <count>
**Fixes Applied**: <count>

## Critical Issues (Immediate Fix Required)
1. **Op**: <name> - **Issue**: View deletion in reshape path
   - File: <path>:LINE
   - Impact: Segmentation fault on model inference
   - Status: FIXED/PENDING

## High Priority Issues
...

## Medium Priority Issues
...

## Low Priority Issues
...

## Safe Operations (No Issues Found)
- op1
- op2
...

## Statistics
- View deletion bugs: X
- Memory leaks: Y
- Double-free risks: Z
- Uninitialized outputs: W

## Testing Recommendations
1. <test needed>
2. <test needed>

## Next Steps
1. <action item>
2. <action item>
```

## Tools and Commands at Your Disposal

### Search for Operations
```bash
# Find op implementation
grep -r "OP_IMPL(add," libnd4j/
grep -r "CUSTOM_OP_IMPL(add," libnd4j/

# Find view-creating operations
grep -n "->reshape(" <file>
grep -n "->permute(" <file>
grep -n "->slice(" <file>

# Find deletions
grep -n "delete " <file>

# Find allocations
grep -n "new NDArray" <file>
```

### Code Reading Tools
```bash
# Read implementation
cat libnd4j/include/ops/declarable/generic/<category>/<op>.cpp

# Search for helper usage
grep -r "MmulHelper\|ReductionHelper\|BroadcastHelper" libnd4j/

# Find workspace macros
grep "ALLOCATE_ON_WORKSPACE\|WORKSPACE" <file>
```

### Debugging Helpers
```bash
# Check if file exists
ls libnd4j/include/ops/declarable/generic/<path>

# Find all op files
find libnd4j/include/ops/declarable/generic -name "*.cpp" | head -20

# Search in headers too
find libnd4j/include/ops/declarable -name "*.h" | grep -i <opname>
```

## Example Analysis (Reference)

Here's how to analyze the "reshape" operation:

```markdown
## Operation: reshape

**File**: `libnd4j/include/ops/declarable/generic/shape/reshape.cpp:45`
**Status**: ⚠️ NEEDS_REVIEW

### Memory Analysis
- Input arrays: 1 (input tensor)
- Output arrays: 1 (pre-allocated by framework)
- Temporary allocations: 0
- Workspace usage: NO

### View Operations Detected
1. Line 67: `auto* reshaped = input->reshape(newShape)`
   - **UNSAFE**: Result might be a view!
   - Stored in local var, no isView() check before scope exit
   - Framework handles cleanup, so SAFE in this case

### Bugs Found
- [x] No bugs - output is framework-owned
- [ ] Memory leak
- [ ] Double-free risk

### Issues:
NONE - The operation correctly uses framework-provided output array and doesn't manually allocate/deallocate.

### Recommended Fix
No fix needed. Code is safe.

### Verification Needed
- [x] Unit test exists
- [ ] AddressSanitizer check - RECOMMENDED
- [ ] Stress test - RECOMMENDED
```

## Important Reminders

1. **When in doubt, DON'T delete** - Let workspace or framework handle it
2. **Always check `isView()`** before calling delete on any NDArray
3. **Pre-allocated outputs** (from OUTPUT_VARIABLE macro) should NEVER be deleted
4. **Workspace-attached arrays** clean themselves up automatically
5. **Views share buffers** - deleting a view deletes the shared buffer

## Deliverables

1. **Individual op reports** (30 markdown files or one combined file with sections)
2. **OP_REVIEW_SUMMARY.md** - Executive summary
3. **FIXES.patch** - Git patch file with all fixes
4. **Test cases** for each bug found
5. **Verification results** from AddressSanitizer/Valgrind if possible

## Success Criteria

- [ ] All 30 ops reviewed and documented
- [ ] All view deletion bugs identified
- [ ] All memory leaks identified
- [ ] Fix recommendations provided for each bug
- [ ] Priority levels assigned
- [ ] Summary report completed

## Start Command

Begin by acknowledging this task and then:
1. Confirm you have access to the codebase files
2. Read ops.txt to see the 30 operations
3. Start with the first HIGH PRIORITY op: **layer_norm**
4. Follow the systematic process above

Good luck! Remember: **Views are friends, not food (for delete operators).**
