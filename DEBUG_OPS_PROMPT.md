# Systematic ND4J/SameDiff C++ Op Implementation Debugging

## Objective
Systematically review and debug all operation implementations found in the SameDiff model to ensure:
1. Proper memory management (no leaks, no dangling pointers)
2. Correct view handling (avoid deleting shared buffers)
3. Proper output array allocation and management
4. Thread-safety where applicable
5. Correct shape inference and validation

## Operations to Debug (30 total)

```
Where
add
assign
cast
concat
create
divide
equals
erf
expand_dims
floordiv
gather
layer_norm
matmul
multiply
permute
range
rank
reduce_prod
reshape
set_scalar
shape_of
size_at
softmax
sqrt
stack
strided_slice
tanh
tensordot
xw_plus_b
```

## Critical Memory Safety Rules

### **RULE 1: NEVER delete view arrays**
- A "view" is an NDArray that shares its underlying buffer with another array
- Views have `isView()` == true
- Deleting a view will corrupt the parent array's buffer
- **ALWAYS check `isView()` before calling delete or any cleanup**

### **RULE 2: Check array ownership**
- Use `isAttached()` to determine if array is workspace-attached
- Workspace-attached arrays are cleaned up by workspace, NOT manually
- Only delete arrays you explicitly allocated with `new`

### **RULE 3: Output array handling**
- If output array is provided as input parameter, do NOT delete it
- If you allocate a new output array, ensure proper cleanup on error paths
- Return allocated arrays to caller - they own the cleanup responsibility

### **RULE 4: Buffer sharing detection**
- Check `buffer()` pointer equality between arrays
- If `array1->buffer() == array2->buffer()`, they share memory
- Reshapes, permutes, slices often create views

## Systematic Debugging Process

For each operation, perform the following checks:

### Phase 1: Code Location
1. Find the op implementation file (usually in `libnd4j/include/ops/declarable/`)
2. Locate the main execution method (usually `execute()` or `calculateOutputShape()`)
3. Identify all helper classes/methods called

### Phase 2: Input Validation
```cpp
// Check for:
- Null pointer checks on input arrays
- Input shape validation
- Data type compatibility checks
- Workspace attachment status
```

### Phase 3: Memory Allocation Analysis
```cpp
// For each allocation, verify:
- Is the array explicitly created with new/NDArrayFactory?
- Is it workspace-attached? (check ALLOCATE_ON_WORKSPACE patterns)
- Who owns cleanup responsibility?
- Are there any early return paths that skip cleanup?
```

### Phase 4: View Detection & Handling
```cpp
// Critical checks:
if (array->isView()) {
    // DO NOT DELETE
    // DO NOT call destructor
    // Only detach from workspace if needed
}

// Common view-creating operations:
- reshape() - MAY create view if contiguous
- permute() - ALWAYS creates view
- slice/strided_slice - ALWAYS creates view
- dup() - creates NEW array (not a view)
- asT<>() - may create view or new array
```

### Phase 5: Output Array Management
```cpp
// Pattern to check:
auto output = OUTPUT_VARIABLE(0);

// Ask:
- Is this a pre-allocated output?
- Do we overwrite it or create new?
- On error, do we clean up partial allocations?
```

### Phase 6: Error Paths
```cpp
// For each return or throw:
- Are all allocated arrays cleaned up?
- Are locks/resources released?
- Are views properly handled (not deleted)?
```

### Phase 7: Buffer Lifecycle
```cpp
// Track buffer pointers:
auto* buffer = array->buffer();

// Ensure:
- Buffer is not deleted while views exist
- Buffer refcount is maintained (if applicable)
- No double-free scenarios
```

## Common Bug Patterns to Look For

### Pattern 1: Deleting Views
```cpp
// BAD:
auto* reshaped = input->reshape(...);
delete reshaped;  // CRASH if reshaped is a view!

// GOOD:
auto* reshaped = input->reshape(...);
if (!reshaped->isView()) {
    delete reshaped;
}
```

### Pattern 2: Workspace Confusion
```cpp
// BAD:
auto* temp = new NDArray(...);  // Not workspace-attached
// ... later in workspace scope
delete temp;  // May double-free if workspace claims it

// GOOD:
auto* temp = NDArrayFactory::create_(...);  // Explicit control
// OR use workspace macros consistently
```

### Pattern 3: Output Not Initialized
```cpp
// BAD:
auto* output = OUTPUT_VARIABLE(0);
// ... conditional logic that may skip assignment
return Status::OK();  // Output may be uninitialized!

// GOOD:
auto* output = OUTPUT_VARIABLE(0);
output->assign(defaultValue);  // Always initialize
// ... then conditional updates
```

### Pattern 4: Shape/Buffer Aliasing
```cpp
// BAD:
auto* a = INPUT_VARIABLE(0);
auto* b = a->reshape(...);  // May be view
a->assign(newValues);  // Modifies b's data too!

// GOOD:
auto* a = INPUT_VARIABLE(0);
auto* b = a->dup();  // Explicit copy
a->assign(newValues);  // Safe
```

### Pattern 5: Early Return Leaks
```cpp
// BAD:
auto* temp = new NDArray(...);
if (error) {
    return Status::FAILURE;  // LEAK!
}
delete temp;

// GOOD:
auto* temp = new NDArray(...);
if (error) {
    if (!temp->isView()) delete temp;
    return Status::FAILURE;
}
delete temp;
```

## Debugging Checklist Per Op

For each operation in ops.txt, create a checklist:

```markdown
## Op: <OP_NAME>

- [ ] File location identified: _______________
- [ ] Input validation present: YES/NO
- [ ] Output allocation strategy: (pre-allocated / new / workspace)
- [ ] View operations detected: (list any reshape/permute/slice calls)
- [ ] View deletion guards present: YES/NO
- [ ] Error path cleanup verified: YES/NO
- [ ] Buffer sharing documented: YES/NO
- [ ] Workspace usage consistent: YES/NO
- [ ] Memory leaks detected: YES/NO
- [ ] Potential double-free detected: YES/NO
- [ ] Thread-safety concerns: YES/NO

### Issues Found:
1.
2.
3.

### Fixes Applied:
1.
2.
3.
```

## Automated Checks

Use these grep patterns to find potential issues:

```bash
# Find delete operations (check if view-guarded)
grep -n "delete " *.cpp *.h

# Find reshape operations (potential views)
grep -n "->reshape(" *.cpp *.h

# Find permute operations (always views)
grep -n "->permute(" *.cpp *.h

# Find slice operations (always views)
grep -n "->slice(" *.cpp *.h
grep -n "strided_slice" *.cpp *.h

# Find manual allocations
grep -n "new NDArray" *.cpp *.h

# Find workspace allocations
grep -n "ALLOCATE" *.cpp *.h

# Find early returns (check for leaks)
grep -n "return.*;" *.cpp | grep -v "return Status::OK"
```

## Testing Strategy

For each op after fixes:

1. **Unit test**: Verify op produces correct output
2. **Memory test**: Run with AddressSanitizer/Valgrind
3. **Leak test**: Run with lifecycle tracking enabled
4. **Stress test**: Run in loop 1000+ times
5. **View test**: Explicitly test with view inputs
6. **Workspace test**: Test with/without workspace scope

## Example Analysis Template

```markdown
# Op: matmul

## Code Location
- File: `libnd4j/include/ops/declarable/generic/matmul.cpp`
- Helper: `libnd4j/include/helpers/MmulHelper.h`

## Memory Flow
1. Input arrays: a (M×K), b (K×N)
2. Output: c (M×N) - PRE-ALLOCATED by framework
3. No temporary allocations in main path
4. Helper may allocate temporary buffers

## View Analysis
- No reshape/permute on inputs
- Helper uses BLAS which doesn't create views
- **SAFE**: No view deletion concerns

## Issues Found
NONE

## Verification
- [x] AddressSanitizer clean
- [x] Valgrind clean
- [x] 10000 iteration stress test passed
```

## Priority Order for Review

### HIGH PRIORITY (Memory-intensive, complex)
1. layer_norm - Multiple allocations, statistics
2. softmax - Reduction, normalization
3. matmul - Large buffers, BLAS interaction
4. tensordot - Complex tensor operations
5. gather - Indexing, potential views
6. concat - Buffer concatenation
7. stack - Array creation

### MEDIUM PRIORITY (Moderate complexity)
8. reshape - VIEW CRITICAL
9. permute - VIEW CRITICAL
10. strided_slice - VIEW CRITICAL
11. expand_dims - VIEW CRITICAL
12. reduce_prod - Reduction logic
13. xw_plus_b - Composite operation

### LOW PRIORITY (Simple operations)
14. add, multiply, divide - Element-wise
15. sqrt, tanh, erf - Element-wise math
16. cast - Type conversion
17. assign - Copy operation
18. equals - Comparison
19. Where - Conditional select

### UTILITY OPS (Infrastructure)
20. create - Allocation
21. shape_of - Metadata
22. size_at - Metadata
23. rank - Metadata
24. range - Generation
25. set_scalar - Simple assignment
26. floordiv - Element-wise

## Documentation Requirements

For each op reviewed, document:

1. **Memory ownership model**: Who owns what
2. **View creation points**: List all view-creating calls
3. **Cleanup responsibility**: Who cleans what
4. **Workspace interaction**: How workspace is used
5. **Thread-safety**: Any shared state or race conditions

## Final Verification

After all ops reviewed:

```bash
# Run full test suite
cd libnd4j
./build.sh -t -l cpp

# Run with memory debugging
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
./build.sh -t -l cpp

# Run SameDiff model inference
cd ../
./kompile model convert --test-inference

# Check for lifecycle leaks
curl -X POST http://localhost:8085/api/lifecycle/enable-ndarray-only
# Run model inference
curl -X POST http://localhost:8085/api/lifecycle/trigger-leak-check
# Check stderr for leak reports
```

## Success Criteria

- [ ] All 30 ops reviewed and documented
- [ ] No view deletion bugs found
- [ ] No memory leaks detected
- [ ] All tests passing
- [ ] AddressSanitizer clean
- [ ] Valgrind clean
- [ ] Lifecycle tracking shows clean shutdown
- [ ] 10000+ iteration stress test passes
- [ ] Model inference completes without crashes

---

**REMEMBER**: When in doubt, DON'T delete. Views and workspace-managed arrays will clean themselves up. Manual deletion should be the exception, not the rule.
