# Claude AI Code Review Bot Setup Guide

**Repository**: `generacclean/react-native-mqtt-mtls`  
**Status**: ✅ Files created, ready to push (branch: `feature/ai-code-review-bot`)  
**Based on**: Working implementation from `neurio/installer-app`

---

## Overview

The AI code review bot automatically reviews pull requests using Claude (via AWS Bedrock) and posts inline comments + summary feedback. It focuses on correctness, native module patterns, binary message handling, security, and mTLS certificate management.

### Capabilities

- ✅ **Automatic reviews** for PRs from allowlisted team members
- ✅ **Manual trigger** via `@claude` mention in PR comments
- ✅ **Inline comments** on specific code issues
- ✅ **Summary feedback** with severity-grouped findings
- ✅ **Context-aware** - reads README, docs/ARCHITECTURE.md, CHANGELOG
- ✅ **Pattern detection** - callback guards, binary detection, type safety

---

## Prerequisites

### 1. AWS Bedrock Access & OIDC Role ✅

**Already configured** for Generac repos:

- **Role Name**: `claude-pr-review-ai-dev-oidc-role-neurio`
- **AWS Account**: `017820692424`
- **ARN**: `arn:aws:iam::017820692424:role/claude-pr-review-ai-dev-oidc-role-neurio`
- **Region**: `us-east-1`
- **Access**: Bedrock models (Claude Sonnet, Opus, Haiku)
- **Trust Policy**: Configured for `neurio/*` and `generacclean/*` repos

**Verification**: Confirm with AWS admin that `generacclean/react-native-mqtt-mtls` is included in the OIDC trust policy.

### 2. GitHub Action Workflow ✅

Uses the shared action:
- **Action**: `neurio/github-action-workflows/claude-pr-review@main`
- **Type**: Private action in `neurio` org with Bedrock support

### 3. GitHub Permissions ✅

Workflows need these permissions (already configured in YAML):

```yaml
permissions:
  contents: read      # Read repository files
  issues: write       # Post comments
  pull-requests: write # Create inline comments
  id-token: write     # AWS OIDC authentication
```

---

## Files Created

All files are created on the `feature/ai-code-review-bot` branch (not yet pushed):

### 1. `.github/ai-review/review-prompt.md`

**Purpose**: Defines review behavior - focus areas, rating criteria, output rules

**Key Features**:
- Loads context from README, docs/ARCHITECTURE.md, CHANGELOG
- Prioritizes correctness, binary handling, native patterns, security
- Checks callback guards (safeInvoke/CallbackGuard patterns)
- Validates topic-based binary detection
- Only posts issues rated >75 with >80% confidence

**Customization**: Edit via normal PR to tune focus, tone, and rules

### 2. `.github/ai-review/reviewers.txt`

**Purpose**: Allowlist of team members whose PRs get automatic reviews

**Current members**:
```
vedgenerac
castulo
benjaminkomen
kuznetsov-sergei
```

**To add members**: Edit file and commit via PR

### 3. `.github/workflows/ai-code-review.yml`

**Purpose**: Automatic review on PR open/sync

**Triggers**:
- PR opened, synchronized, or marked ready for review
- Target branch: `main`
- Author is on allowlist
- PR is not draft

**Configuration**:
- Timeout: 20 minutes
- Max turns: 60 (thorough for native code)
- Allowed tools: Inline comments, Read, Glob, Grep, gh commands
- Concurrency: Cancels previous run when new commits pushed

### 4. `.github/workflows/claude-code-review.yml`

**Purpose**: Manual trigger via `@claude` mention

**Triggers**:
- Comment contains `@claude` on PR or review comment
- Works for any team member (not restricted by allowlist)

**Usage**: Post comment `@claude please review this` on any PR

---

## Setup Steps

### Step 1: Verify AWS Access

Confirm with your AWS admin that the OIDC trust policy includes `generacclean/react-native-mqtt-mtls`.

**To check yourself** (if you have AWS access):

```bash
aws iam get-role --role-name claude-pr-review-ai-dev-oidc-role-neurio \
  --query 'Role.AssumeRolePolicyDocument.Statement[0].Condition' \
  --output json
```

Look for `generacclean/react-native-mqtt-mtls:*` in the conditions.

### Step 2: Push the Branch

```bash
# Currently on: feature/ai-code-review-bot
git status
# Should show 4 new files:
# .github/ai-review/review-prompt.md
# .github/ai-review/reviewers.txt
# .github/workflows/ai-code-review.yml
# .github/workflows/claude-code-review.yml

# Review files (if needed)
git diff --staged

# Commit and push
git add .github/
git commit -m "feat: add Claude AI code review bot configuration

- Add automatic review workflow for allowlisted team members
- Add manual @claude trigger workflow
- Configure review prompt focused on native code, binary handling, security
- Add team reviewers allowlist (vedgenerac, castulo, benjaminkomen, kuznetsov-sergei)"

git push origin feature/ai-code-review-bot
```

### Step 3: Create PR

```bash
# Create PR from feature branch to main
gh pr create \
  --title "feat: Add Claude AI code review bot" \
  --body "$(cat <<'PRBODY'
## Summary

Adds automated AI code review bot for react-native-mqtt-mtls PRs using Claude via AWS Bedrock.

## Changes

### Workflows
- `.github/workflows/ai-code-review.yml` - Automatic review on PR open/sync
- `.github/workflows/claude-code-review.yml` - Manual trigger via @claude mention

### Configuration
- `.github/ai-review/review-prompt.md` - Review focus areas and rules
- `.github/ai-review/reviewers.txt` - Team allowlist (vedgenerac, castulo, benjaminkomen, kuznetsov-sergei)

## Review Focus

The bot prioritizes:
1. **Correctness & bugs** - Edge cases, memory leaks, callback double-invocation
2. **Binary handling** - Topic-based detection, protobuf classification
3. **Native patterns** - Callback guards (safeInvoke/CallbackGuard)
4. **Security** - mTLS, certificate/key handling
5. **Type safety** - Uint8Array vs ArrayBuffer
6. **Testing** - Coverage for edge cases
7. **Documentation** - API changes, CHANGELOG

## How It Works

### Automatic Review (ai-code-review.yml)
- Runs on PRs from allowlisted authors targeting main
- Skips draft PRs
- Posts inline comments on specific issues (rated >75, confidence >80%)
- Posts summary comment with severity-grouped findings
- Timeout: 20 minutes
- Max turns: 60 (thorough for native code)

### Manual Trigger (claude-code-review.yml)
- Comment `@claude` on any PR to trigger review
- Works for any team member (not restricted by allowlist)

## Testing Plan

- [ ] Create test PR from allowlisted author → Verify automatic review runs
- [ ] Create test PR from non-allowlisted author → Verify review skipped
- [ ] Post `@claude` comment → Verify manual trigger works
- [ ] Check inline comments are posted on code
- [ ] Check summary comment is posted

## Prerequisites

- ✅ AWS OIDC role configured: `claude-pr-review-ai-dev-oidc-role-neurio`
- ⚠️ Need to verify: `generacclean/react-native-mqtt-mtls` in OIDC trust policy
- ✅ GitHub Action: `neurio/github-action-workflows/claude-pr-review@main`
- ✅ Permissions: contents:read, issues:write, pull-requests:write, id-token:write

## Cost Estimate

- Small PR (<50 lines): $0.10 - $0.30
- Medium PR (50-200 lines): $0.50 - $1.50
- Large PR (200-500 lines): $1.50 - $3.00

## Rollout Plan

1. Merge this PR
2. Test on a small PR from allowlisted author
3. Monitor first few reviews for signal/noise ratio
4. Adjust review prompt if needed
5. Add more team members to allowlist

## References

- Based on: `neurio/installer-app` - `.github/workflows/ces-ai-code-review.yml`
- Action: https://github.com/neurio/github-action-workflows
- AWS Bedrock: https://aws.amazon.com/bedrock/
PRBODY
)" \
  --base main \
  --head feature/ai-code-review-bot
```

### Step 4: Test the Setup

After merging, test with:

#### Test 1: Automatic Review (Allowlisted Author)

1. Create a test branch with a small code change
2. Open PR to `main` from your account (must be in allowlist)
3. Verify workflow runs in Actions tab
4. Check for inline comments and summary

#### Test 2: Allowlist Check (Non-allowlisted Author)

1. Ask a teammate NOT on allowlist to open a test PR
2. Verify "gate" job logs show "not on allowlist - skipping"
3. Verify "review" job does NOT run

#### Test 3: Manual Trigger

1. On any PR, post comment: `@claude please review this`
2. Verify `claude-code-review.yml` workflow triggers
3. Check Actions tab for running workflow
4. Check for inline comments and summary

#### Test 4: Draft PR Skip

1. Create a draft PR from allowlisted author
2. Verify workflow does NOT run
3. Mark PR as "Ready for review"
4. Verify workflow now triggers

---

## Expected Review Output

### Inline Comments

Posted on specific lines with issues:

```markdown
[🏗️ **design**] This callback lacks guard pattern - see safeInvoke() usage in line 124.

Without atomic guard, network disconnects could trigger both success and error callbacks,
causing React Native bridge crash (SIGABRT).

Recommended: Wrap with safeInvoke(callback, callbackFired, args)
```

### Summary Comment

Posted once per review:

```markdown
_🤖 Automated code review. Addressing it doesn't guarantee a merge; a human still owns approval._

## Overview
This PR adds topic-based binary detection for protobuf messages...

## Issues Found

### Important (76-90)
1. **Missing callback guard** (line 234) - Publish callback lacks safeInvoke wrapper
2. **Type mismatch** (line 89) - Returns ArrayBuffer, should be Uint8Array

### Critical (91-100)
None found

## Assessment
Found 2 important issues requiring attention. All related to callback safety and type correctness.
```

---

## Customization

### Adjusting Review Focus

Edit `.github/ai-review/review-prompt.md` to:

- Change priority of focus areas
- Add library-specific patterns
- Adjust rating threshold (currently >75)
- Change tone/verbosity

**Example**: Add new focus area:

```markdown
9. **React Native bridge** — Minimize bridge crossings, batch operations, avoid synchronous calls
```

Changes take effect on the next PR after merging.

### Adjusting Allowlist

Edit `.github/ai-review/reviewers.txt`:

```bash
# Add new member
echo "newteammember" >> .github/ai-review/reviewers.txt
git add .github/ai-review/reviewers.txt
git commit -m "chore: add newteammember to AI review allowlist"
git push
```

### Adjusting Turn Limit

In workflow YAML, change `max-turns`:

```yaml
max-turns: "60"  # Current: good for native modules
max-turns: "40"  # Lower: faster, cheaper, less thorough
max-turns: "80"  # Higher: more thorough, for very large PRs
```

### Cost Management

**Estimated costs** (AWS Bedrock pricing):
- Small PR (<50 lines): ~$0.10 - $0.30
- Medium PR (50-200 lines): ~$0.50 - $1.50
- Large PR (200-500 lines): ~$1.50 - $3.00
- Very large PR (500+ lines): ~$3.00 - $5.00

**To reduce costs**:
1. Keep allowlist small (already implemented)
2. Lower `max-turns` in workflow (currently 60)
3. Skip draft PRs (already implemented)
4. Increase rating threshold in prompt (>80 instead of >75)

---

## Troubleshooting

### Workflow Not Running

**Check**:
1. PR is targeting `main` branch
2. PR is not a draft
3. PR author is on allowlist
4. Workflow file has correct syntax

**View logs**:
```bash
gh run list --workflow=ai-code-review.yml
gh run view <run-id> --log
```

### AWS Authentication Failures

**Error**: `Error: Could not assume role`

**Fix**:
1. Verify OIDC role name: `claude-pr-review-ai-dev-oidc-role-neurio`
2. Check with AWS admin that `generacclean/react-native-mqtt-mtls` is in OIDC trust policy
3. Verify `id-token: write` permission in workflow

**To add repo to OIDC trust policy** (AWS admin):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::017820692424:oidc-provider/token.actions.githubusercontent.com"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringLike": {
        "token.actions.githubusercontent.com:sub": [
          "repo:neurio/*:*",
          "repo:generacclean/*:*"  ← Should include generacclean
        ]
      }
    }
  }]
}
```

### No Comments Posted

**Check**:
1. Workflow completed successfully (Actions tab)
2. Bot has `pull-requests: write` permission
3. Review found issues above threshold (check logs for "NO ISSUES FOUND")
4. Not hitting GitHub API rate limits

### Action Not Found

**Error**: `neurio/github-action-workflows/claude-pr-review@main not found`

**Fix**: The action is in a private repo. Contact `neurio` org maintainers to:
1. Grant `generacclean` org access to `neurio/github-action-workflows`
2. Or, copy action into `generacclean` org

---

## Best Practices

1. **Start with allowlist** - Test with small team before expanding
2. **Iterate on prompt** - Watch first few reviews and adjust signal/noise
3. **Monitor costs** - Check AWS billing for Bedrock usage
4. **Train team** - Bot is not a replacement for human review
5. **Handle false positives gracefully** - Bot can be wrong; humans decide
6. **Keep prompt up to date** - Update as codebase patterns evolve
7. **Use manual trigger for edge cases** - `@claude` for on-demand deep dives

---

## Review Prompt Highlights

The review prompt is tailored specifically for this library:

### Critical Patterns Checked

1. **Callback Guards** (Rating: 91-100 if missing)
   - Android: `safeInvoke(callback, AtomicBoolean, args)`
   - iOS: `CallbackGuard` wrapper with `NSLock`
   - iOS Connect: Shared settled guard for mutual exclusion

2. **Binary Detection** (Rating: 91-100 if broken)
   - Topic patterns FIRST (deterministic)
   - Binary: `/proto/*`, `/device*`, `/firmware*`, `/ota*`
   - Text: `/status*`, `/config*`, `/json*`
   - UTF-8 fallback ONLY for unknown topics

3. **Type Safety** (Rating: 76-90 if wrong)
   - Must use `Uint8Array`, not `ArrayBuffer`
   - Binary messages as `Uint8Array`, text as `string`
   - `isBinary` flag signals type

### Focus Priority

1. **Correctness & bugs** (91-100 for crashes, memory leaks, threading issues)
2. **Binary message handling** (91-100 for misclassification)
3. **Native module patterns** (91-100 for missing callback guards)
4. **Security** (91-100 for key leaks, TLS misconfig)
5. **TypeScript types** (76-90 for type mismatches)
6. **Testing** (51-75 for missing tests)
7. **Documentation** (26-50 for missing docs)
8. **Performance** (51-75 for inefficiencies)

---

## Support & References

### For Issues With

- **AWS/OIDC setup**: Contact AWS admin or DevOps team
- **Workflow configuration**: Check this guide or installer-app workflows
- **Action not working**: Contact `neurio/github-action-workflows` maintainers
- **Claude model access**: Verify Bedrock access in AWS console
- **Prompt tuning**: Edit `.github/ai-review/review-prompt.md` via PR

### References

- **Working example**: `neurio/installer-app` - `.github/workflows/ces-ai-code-review.yml`
- **This repo setup**: Branch `feature/ai-code-review-bot`
- **Anthropic Claude Code**: https://github.com/anthropics/claude-code-action
- **AWS Bedrock**: https://aws.amazon.com/bedrock/
- **GitHub OIDC**: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services

---

## Summary

### What's Configured ✅

- ✅ Automatic review workflow for allowlisted PRs
- ✅ Manual `@claude` trigger workflow
- ✅ Review prompt tailored for native MQTT module
- ✅ Team allowlist (4 members)
- ✅ AWS OIDC role integration
- ✅ GitHub permissions configured

### What's Needed ⚠️

- ⚠️ Verify AWS OIDC trust policy includes this repo (contact AWS admin)
- ⚠️ Push branch to remote
- ⚠️ Create and merge PR
- ⚠️ Test with small PR from allowlisted author
- ⚠️ Monitor first few reviews and adjust prompt if needed

### Next Steps

1. **Review files** on `feature/ai-code-review-bot` branch
2. **Push branch** to remote
3. **Create PR** using command from Step 3 above
4. **Merge PR** after review
5. **Test** with small PR from allowlisted author
6. **Monitor** and iterate on prompt as needed

---

**Branch**: `feature/ai-code-review-bot`  
**Status**: Ready to push  
**Files**: 4 new files in `.github/`  
**Based on**: Working setup from `neurio/installer-app`
