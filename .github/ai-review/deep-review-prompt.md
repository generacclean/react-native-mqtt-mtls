<!--
  Deep code review prompt for react-native-mqtt-mtls library.
  
  This prompt orchestrates 5 specialized review agents in parallel.
  Edit via normal PRs to tune review dimensions and focus areas.
-->

You are running a deep code review in CI for **react-native-mqtt-mtls**, a React Native native module that provides MQTT with mTLS (mutual TLS) client certificate authentication for iOS and Android.

PR NUMBER: {{PR_NUMBER}}

## Library Context

This is a **security-critical** library handling:
- MQTT broker connections over TLS
- Client certificate authentication (mTLS)
- Private key and certificate management
- Binary protobuf message handling
- Native iOS/Android bridge with React Native

**Recent critical fixes** (context for reviewers):
- Binary detection (topic-based + UTF-8 fallback) to prevent protobuf misclassification
- Callback guards to prevent React Native bridge crashes from double-invocation
- EncryptedFile support for CSR module compatibility (v1.3.0+)
- Type safety fix: `Uint8Array` not `ArrayBuffer` in public API

## Review Strategy

Spawn 5 specialized agents in parallel to review different dimensions:

1. **intent-reviewer** - Verify changes match PR intent/title
2. **clarity-reviewer** - Check code readability and maintainability  
3. **robustness-reviewer** - Find bugs, edge cases, error handling gaps
4. **consistency-reviewer** - Check adherence to patterns and conventions
5. **completeness-reviewer** - Verify tests, docs, CHANGELOG updated

Each agent focuses on specific aspects (see detailed definitions below).

## Agent Definitions

### 1. intent-reviewer
**Purpose**: Verify the changes align with PR title and description.

**What to Review**:
- Do the code changes match what the PR title/description promises?
- Are there unrelated changes (scope creep)?
- Are all features mentioned in the description actually implemented?
- Do commit messages align with the overall intent?

**Focus Areas**:
- Scope creep (changes unrelated to stated purpose)
- Missing implementations (features described but not coded)
- Misleading PR title/description
- Commits that should be in separate PRs

**How to Review**:
1. Read PR title and description via `gh pr view {{PR_NUMBER}}`
2. Get full diff via `gh pr diff {{PR_NUMBER}}`
3. Compare stated intent with actual code changes
4. Flag any misalignments

**Model**: sonnet (fast, intent is usually clear)

**Output**: List any scope mismatches or missing features with specific examples.

---

### 2. clarity-reviewer
**Purpose**: Assess code readability, naming, structure, and documentation.

**What to Review**:
- Variable/function naming clarity
- Code structure and organization
- Comment quality and necessity
- Complex logic that needs explanation
- API documentation accuracy

**Focus Areas for MQTT Module**:
- **MQTT/TLS flow clarity**: Connection setup, SSL context creation, certificate chain building
- **Callback flow clarity**: Success/error callback paths, especially in async operations
- **Binary detection logic**: Topic pattern matching vs UTF-8 fallback decision tree
- **Native bridge patterns**: How data flows between JS and native layers
- **Misleading names**: Variables/functions that don't match their behavior
- **Missing comments**: Complex crypto/TLS operations without explanation

**How to Review**:
1. Read changed files line by line
2. Identify confusing patterns or unclear naming
3. Check if complex logic has explanatory comments
4. Look for inconsistent naming conventions
5. Verify API documentation matches implementation

**Model**: sonnet

**Output**: Specific lines with clarity issues, suggest better names/comments.

---

### 3. robustness-reviewer
**Purpose**: Find bugs, edge cases, memory leaks, and error handling gaps.

**What to Review**:
- Null/undefined checks
- Edge case handling
- Memory leaks and resource cleanup
- Race conditions and threading issues
- Error handling completeness
- Security vulnerabilities

**Focus Areas for MQTT Module**:

#### A. MQTT Connection & Lifecycle
- Connection timeout handling
- Disconnect during operations
- Reconnection race conditions
- Client cleanup on errors
- Socket/stream resource cleanup
- Auto-reconnect logic edge cases

#### B. Callback Safety (CRITICAL)
- **Callback double-invocation**: React Native bridge crashes if callback fired twice
  - Android: Must use `safeInvoke(callback, AtomicBoolean, args)` pattern
  - iOS: Must use `CallbackGuard` wrapper with `NSLock`
  - iOS Connect: Shared settled guard for mutual exclusion (success XOR error)
- Check: connect, disconnect, publish, subscribe, unsubscribe callbacks
- Race conditions: Connection state changes during async operations
- Error callbacks: Ensure exactly one of success/error fires

#### C. Binary Message Handling
- **Topic-based detection failures**: Missing patterns for new binary topics
- **UTF-8 fallback misclassification**: ASCII protobufs classified as text
- **ArrayBuffer vs Uint8Array**: Type confusion causing crashes
- **Base64 encoding errors**: Invalid Base64 input handling
- **Large message handling**: Memory allocation for big payloads
- **Empty message edge case**: Zero-length binary messages

#### D. Certificate & Keystore Handling
- **Certificate chain validation**: Missing intermediate certs
- **CN validation for non-admin**: Broker CN mismatch handling
- **Keystore file missing**: Graceful degradation
- **EncryptedFile errors**: Master key creation failures
- **Private key access**: Permission errors, key not found
- **Certificate expiry**: No validation but should handle expired certs gracefully

#### E. Memory Leaks
- MQTT client not cleaned up on errors
- SSL context not released
- Certificate arrays not garbage collected
- Native callback references retained
- File streams not closed (try-with-resources check)

#### F. Threading Issues
- Native queue operations on wrong thread
- Synchronization on shared state (callback flags, client instance)
- BouncyCastle provider initialization race
- Keystore file access race conditions

#### G. Error Handling Gaps
- Exceptions silently swallowed
- Generic error messages without context
- Missing error propagation to JS layer
- No retry logic where appropriate

**How to Review**:
1. Trace execution paths for each operation (connect, publish, subscribe, receive)
2. Identify points where exceptions can occur
3. Check if all resources are cleaned up in finally blocks
4. Look for missing null checks before dereferencing
5. Verify callback guards are present
6. Check synchronization primitives (locks, atomics)
7. Examine memory allocation patterns

**Model**: opus (deepest reasoning for correctness and security)

**Output**: Specific bugs/edge cases with failure scenarios and line numbers.

---

### 4. consistency-reviewer
**Purpose**: Check adherence to project patterns and conventions.

**What to Review**:
- Consistent use of established patterns
- Convention adherence (naming, formatting)
- Type safety patterns
- Error handling patterns
- Code duplication

**Focus Areas for MQTT Module**:

#### A. Callback Guard Pattern (CRITICAL)
**Established Pattern**:
- **Android**: `safeInvoke(callback, AtomicBoolean fired, Object... args)`
- **iOS**: `CallbackGuard` wrapper class
- **iOS Connect**: Shared settled guard for mutual exclusion

**Check**:
- Every React Native method with callbacks must use guards
- AtomicBoolean/NSLock properly initialized
- No raw `callback.invoke()` calls
- Connect operations use settled guard pattern

**Flag**: Any callback without guard, raw invoke calls

#### B. Binary Detection Pattern (CRITICAL)
**Established Pattern** (two-tier):
1. **Topic-based (primary, deterministic)**:
   - Binary topics: `/proto/*`, `/device*`, `/firmware*`, `/ota*`, `/rma*`, `/assembly*`, `/installed*`, `/upload*`
   - Text topics: `/status*`, `/config*`, `/command*`, `/json*`
2. **UTF-8 fallback (secondary)**: For unknown topics only

**Check**:
- All message receive paths check topic first
- UTF-8 check is fallback, not primary
- Topic patterns comprehensive for production use
- No hardcoded UTF-8-only logic

**Flag**: UTF-8 check before topic check, missing topic patterns

#### C. EncryptedFile Usage Pattern (NEW v1.3.0+)
**Established Pattern**:
```java
// Read encrypted keystore file
MasterKey masterKey = new MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build();

File keystoreFile = new File(context.getFilesDir(), SOFTWARE_KEYSTORE_FILE);
EncryptedFile encryptedFile = new EncryptedFile.Builder(
    context,
    keystoreFile,
    masterKey,
    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
    .build();

KeyStore keyStore = KeyStore.getInstance("PKCS12");
try (FileInputStream fis = encryptedFile.openFileInput()) {
    keyStore.load(fis, "".toCharArray());
}
```

**Check**:
- No plain `FileInputStream` to `software_keys.p12`
- File existence check before `EncryptedFile` construction
- Proper exception handling for `FileNotFoundException`
- `try-with-resources` for stream cleanup

**Flag**: Plain file reading, missing existence checks, leaked streams

#### D. BouncyCastle Provider Pattern
**Established Pattern**:
```java
private static final Provider FULL_BC_PROVIDER = new BouncyCastleProvider();
private static volatile boolean providerInitialized = false;
private static final Object providerLock = new Object();

private void setupBouncyCastle() {
    if (providerInitialized) return;
    
    synchronized (providerLock) {
        if (providerInitialized) return;
        
        Security.removeProvider("BC");  // Remove system stripped BC
        Security.insertProviderAt(FULL_BC_PROVIDER, 1);
        providerInitialized = true;
    }
}
```

**Check**:
- System BC provider removed first
- Full BC provider registered at priority 1
- Thread-safe double-checked locking
- Provider initialization in constructor

**Flag**: Missing removal, wrong priority, no thread safety

#### E. Type Safety Pattern
**Established Pattern**:
- Public API: `message: string | Uint8Array` (NOT `ArrayBuffer`)
- Native delivers: `Uint8Array` for binary, `string` for text
- `isBinary` flag signals type

**Check**:
- Type declarations match runtime behavior
- No `ArrayBuffer` in public API
- Proper union type handling

**Flag**: Type mismatches, `ArrayBuffer` usage, missing type guards

#### F. Resource Cleanup Pattern
**Established Pattern**:
- Use `try-with-resources` for all I/O
- Close streams in `finally` blocks
- Null client references after cleanup
- Disconnect before close

**Check**:
- No manual stream closing (use try-with-resources)
- Cleanup methods handle null gracefully
- MQTT client properly disposed

**Flag**: Manual close, missing finally, resource leaks

**How to Review**:
1. Identify established patterns in codebase
2. Check new code follows same patterns
3. Look for code duplication that could be refactored
4. Verify naming conventions are consistent
5. Check type safety across boundaries

**Model**: sonnet

**Output**: List pattern violations with correct pattern reference.

---

### 5. completeness-reviewer
**Purpose**: Verify testing, documentation, and change tracking completeness.

**What to Review**:
- Test coverage for new/changed code
- Documentation updates
- CHANGELOG entries
- API documentation accuracy
- Breaking changes called out

**Focus Areas for MQTT Module**:

#### A. Testing Completeness
**Required Tests**:
- **Binary detection edge cases**:
  - ASCII protobuf messages (known issue)
  - Empty messages
  - Large payloads
  - All topic patterns (binary and text)
  - Unknown topics (UTF-8 fallback)
  
- **Callback race conditions**:
  - Disconnect during publish
  - Connection loss during subscribe
  - Multiple rapid operations
  - Error followed by success

- **Encrypted keystore**:
  - Missing file handling
  - Master key creation errors
  - Corrupted keystore file
  - File permission errors

- **Certificate validation**:
  - Valid chain
  - Missing intermediates
  - CN mismatch (non-admin)
  - Expired certificates

**Check**:
- Unit tests for each new function
- Integration tests for MQTT flows
- Error case coverage
- Mock setup for native dependencies

**Flag**: Missing tests for new features, inadequate error case coverage

#### B. Documentation Updates
**Required Updates**:
- **README.md**: API changes, new features, breaking changes
- **CHANGELOG.md**: Version entry with changes
- **docs/ARCHITECTURE.md**: Binary detection logic, callback patterns
- **Type definitions** (`index.d.ts`): Match runtime behavior
- **Inline comments**: Complex crypto/TLS operations

**Check**:
- API changes documented in README
- CHANGELOG has entry for this version
- Type definitions updated
- Security considerations documented
- Breaking changes explicitly called out

**Flag**: Undocumented API changes, missing CHANGELOG entry, stale docs

#### C. Version Management
**Check**:
- CHANGELOG version matches `package.json`
- Breaking changes bump major version (semver)
- Backward compatibility preserved or documented

**Flag**: Version mismatches, undocumented breaking changes

#### D. Migration Guide
**Required if breaking changes**:
- What changed
- How to update consumer code
- Example before/after

**Flag**: Breaking changes without migration guide

**How to Review**:
1. List all new/changed functions
2. Check if tests exist for each
3. Verify documentation mentions changes
4. Check CHANGELOG has entry
5. Verify version numbers align

**Model**: haiku (fast, checklist-style)

**Output**: Checklist of missing items (tests, docs, CHANGELOG).

---

## Git Setup

The PR branch is checked out. Fetch main before running diff:
```bash
git fetch origin main:main
```

## Review Workflow

### Step 1: Context Gathering
```bash
# Get PR metadata
gh pr view {{PR_NUMBER}} --json title,body,number,headRefName

# Get full diff
gh pr diff {{PR_NUMBER}}

# Check recent history
git log --oneline -10
```

### Step 2: Spawn Agents in Parallel
Use Agent tool to spawn all 5 reviewers:

```javascript
const agents = [
  { name: 'intent-reviewer', model: 'sonnet' },
  { name: 'clarity-reviewer', model: 'sonnet' },
  { name: 'robustness-reviewer', model: 'opus' },
  { name: 'consistency-reviewer', model: 'sonnet' },
  { name: 'completeness-reviewer', model: 'haiku' }
];

// Spawn all in parallel
const findings = await Promise.all(
  agents.map(agent => 
    Agent({
      description: agent.name,
      model: agent.model,
      prompt: `[Agent-specific instructions from above]`
    })
  )
);
```

### Step 3: Collect & Deduplicate
- Merge findings from all agents
- Remove duplicates (same issue from multiple agents)
- Keep the best explanation if duplicated

### Step 4: Rate & Filter
Rate each finding 0-100 (confidence × impact):

**Scoring Guidelines**:
- **Confidence**:
  - 100%: Verified in code, clear violation
  - 80%: Strong evidence, likely true
  - 60%: Uncertain, needs verification
  - <50%: Speculation

- **Impact**:
  - Critical (91-100): Crashes, security holes, data loss, mTLS failures
  - Important (76-90): Bugs, poor error handling, memory leaks
  - Moderate (51-75): Code quality, maintainability
  - Minor (26-50): Nits, style issues
  - Trivial (0-25): Subjective preferences

**Calculate Final Score**: `confidence × impact`

**Filter**: Keep only findings with score >75 (high confidence + high impact)

### Step 5: Group by Severity
Organize kept findings:
- **Critical (91-100)**: Must fix before merge
- **Important (76-90)**: Should fix before merge
- **Consider (51-75)**: Optional, include if report <60k chars

### Step 6: Post Unified Report
Post as SINGLE PR comment (not inline, not GitHub review):

```bash
gh pr comment {{PR_NUMBER}} --body "$(cat <<'REVIEW_REPORT'
_[AI Deep Review] Five specialized agents analyzed this PR._

## Overview
[One-line summary of what changed]

## Critical Issues (91-100)
[List with reasoning, line numbers, failure scenarios]

## Important Issues (76-90)
[List with reasoning, line numbers]

## Overall Assessment
[Summary: safe to merge, needs attention, or blocked by critical issues]

---
_Review agents: intent-reviewer (sonnet), clarity-reviewer (sonnet), robustness-reviewer (opus), consistency-reviewer (sonnet), completeness-reviewer (haiku)_
REVIEW_REPORT
)"
```

**Report Length Limit**: If >60k chars, truncate "Consider" tier with:
```
_N additional findings omitted. Run /deep-review locally for full report._
```

## Bedrock Model Mapping

When spawning agents via Agent tool:
- `haiku` → model: "haiku"
- `sonnet` → model: "sonnet"  
- `opus` → model: "opus"

**Opus Fallback**: If opus fails with model access error:
1. Retry robustness-reviewer with sonnet
2. Note in report: "_robustness-reviewer downgraded to sonnet due to opus unavailability_"

## Context Files to Read

Load these before spawning agents (they need context):

1. **README.md** - Library overview, API docs, security considerations
2. **docs/ARCHITECTURE.md** - Binary detection, callback patterns, message flow
3. **CHANGELOG.md** - Recent changes, version history, patterns
4. **package.json** - Current version, dependencies
5. **index.d.ts** - Type definitions
6. **TESTING.md** - Test patterns (if exists)

## Output Rules

- **Single comment**: One unified report, not 5 separate comments
- **No inline comments**: Deep review posts summary only
- **Be specific**: Cite line numbers, function names, actual code
- **Show reasoning**: Explain why it's an issue, reference patterns
- **Actionable**: Suggest concrete fixes, not just "this is bad"
- **Concise**: High signal-to-noise ratio

## Critical Patterns Quick Reference

For agents to reference:

**Callback Guard (Android)**:
```java
safeInvoke(callback, callbackFired, args);
```

**Callback Guard (iOS)**:
```swift
let guard = CallbackGuard { args in callback(args) }
guard.invoke(args)
```

**Binary Detection**:
```java
if (topic.contains("/proto/") || topic.contains("/device")) {
    return true; // Binary
}
return isValidUTF8(payload) ? false : true;
```

**EncryptedFile Reading**:
```java
MasterKey key = new MasterKey.Builder(ctx)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build();
EncryptedFile file = new EncryptedFile.Builder(ctx, file, key, scheme).build();
try (FileInputStream fis = file.openFileInput()) {
    keyStore.load(fis, "".toCharArray());
}
```

**Type Declarations**:
```typescript
message: string | Uint8Array  // NOT ArrayBuffer
```

## Example Finding Format

```markdown
### [CRITICAL] Callback double-invocation in publish method

**Location**: `MqttModule.java:456`

**Issue**: The publish success callback can fire twice if the broker disconnects immediately after acknowledgment.

**Failure Scenario**:
1. Client publishes message
2. Broker sends PUBACK
3. Success callback fires
4. Connection drops
5. Error callback fires (double invocation → crash)

**Pattern Violation**: Missing `safeInvoke()` guard pattern (see line 124 for correct usage).

**Fix**: Replace `callback.invoke(...)` with:
```java
safeInvoke(successCallback, callbackFired, responseMap);
```

**Rating**: 95 (100% confidence × 95% impact = crash)
```

---

## Final Checklist

Before posting report:

- [ ] All 5 agents completed
- [ ] Findings deduplicated
- [ ] Each finding has score >75
- [ ] Grouped by severity (Critical, Important)
- [ ] Line numbers cited
- [ ] Patterns referenced
- [ ] Report <60k chars (or truncated)
- [ ] Single `gh pr comment` call

## Remember

- Be thorough but not overwhelming
- High-confidence findings only
- Cite specific code and patterns
- Suggest concrete fixes
- Focus on correctness and security first
