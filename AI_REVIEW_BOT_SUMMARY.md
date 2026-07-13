# AI Code Review Bot - Quick Summary

**Branch**: `feature/ai-code-review-bot` (created from `main`)  
**Status**: ✅ Files created, NOT COMMITTED yet  
**Ready**: Push and create PR when ready

---

## Files Created (5 total)

### Workflow Files (.github/workflows/)

1. **ai-code-review.yml** - Automatic review on PR open/sync
   - Runs for allowlisted authors only
   - Targets `main` branch
   - Skips draft PRs
   - Timeout: 20 min, Max turns: 60

2. **claude-code-review.yml** - Manual trigger via `@claude` mention
   - Works for any team member
   - Triggered by commenting `@claude` on PR

### Configuration Files (.github/ai-review/)

3. **review-prompt.md** - Review instructions (4.7KB)
   - Focus areas: correctness, binary handling, native patterns, security
   - Checks callback guards (safeInvoke/CallbackGuard)
   - Validates topic-based binary detection
   - Only posts issues rated >75, confidence >80%

4. **reviewers.txt** - Team allowlist
   - vedgenerac
   - castulo
   - benjaminkomen
   - kuznetsov-sergei

### Documentation

5. **AI_CODE_REVIEW_SETUP_GUIDE.md** - Complete setup guide (109KB)
   - Prerequisites and verification steps
   - Testing procedures
   - Customization options
   - Troubleshooting guide

---

## What It Does

### Automatic Review (ai-code-review.yml)

When a PR is opened/updated by an allowlisted author:
1. ✅ Loads review prompt from `.github/ai-review/review-prompt.md`
2. ✅ Reviews code changes with Claude (AWS Bedrock)
3. ✅ Posts inline comments on specific issues
4. ✅ Posts summary comment with severity-grouped findings
5. ✅ Only posts high-confidence issues (>75 rating, >80% confidence)

### Manual Trigger (claude-code-review.yml)

When someone comments `@claude` on a PR:
1. ✅ Triggers comprehensive review
2. ✅ Works for any team member (not restricted by allowlist)
3. ✅ Posts inline comments and summary

---

## Review Focus

### Priority 1: Critical Issues (91-100)
- Callback double-invocation (missing guards)
- Binary misclassification (broken topic patterns)
- Memory leaks, crashes
- mTLS security issues

### Priority 2: Important Issues (76-90)
- Missing callback guards on new operations
- Type safety (Uint8Array vs ArrayBuffer)
- Native bridge pattern violations
- Threading issues

### Priority 3: Lower Priority (51-75)
- Missing tests
- Performance improvements
- Documentation gaps

---

## Next Steps

### 1. Review Files (Optional)

```bash
# Check what's been created
git status

# Review workflow files
cat .github/workflows/ai-code-review.yml
cat .github/workflows/claude-code-review.yml

# Review configuration
cat .github/ai-review/review-prompt.md
cat .github/ai-review/reviewers.txt

# Read full guide
open AI_CODE_REVIEW_SETUP_GUIDE.md
```

### 2. Commit and Push (When Ready)

```bash
# Stage files
git add .github/ AI_CODE_REVIEW_SETUP_GUIDE.md

# Commit
git commit -m "feat: add Claude AI code review bot configuration

- Add automatic review workflow for allowlisted team members
- Add manual @claude trigger workflow
- Configure review prompt focused on native code, binary handling, security
- Add team reviewers allowlist (vedgenerac, castulo, benjaminkomen, kuznetsov-sergei)
- Add comprehensive setup guide"

# Push
git push origin feature/ai-code-review-bot
```

### 3. Create PR

```bash
# Use gh CLI to create PR
gh pr create \
  --title "feat: Add Claude AI code review bot" \
  --body "See AI_CODE_REVIEW_SETUP_GUIDE.md for details" \
  --base main \
  --head feature/ai-code-review-bot
```

Or manually create PR on GitHub with description from setup guide.

### 4. After Merge - Test It

**Test 1**: Automatic review
- Create a test PR from your account (you're on allowlist)
- Verify workflow runs in Actions tab
- Check for inline comments and summary

**Test 2**: Manual trigger
- Comment `@claude please review this` on any PR
- Verify workflow runs
- Check for comments

**Test 3**: Allowlist check
- Ask someone NOT on allowlist to open a PR
- Verify review is skipped (check logs)

---

## Cost Estimate

Per PR based on size:
- Small (<50 lines): $0.10 - $0.30
- Medium (50-200 lines): $0.50 - $1.50
- Large (200-500 lines): $1.50 - $3.00
- Very large (500+ lines): $3.00 - $5.00

**With 4-person allowlist**, expect ~$5-20/week depending on PR frequency.

---

## Prerequisites Check

### Required (Must Verify)

- ⚠️ **AWS OIDC Trust Policy**: Confirm `generacclean/react-native-mqtt-mtls` is included
  - Contact: AWS admin or DevOps team
  - Role: `claude-pr-review-ai-dev-oidc-role-neurio`
  - Trust policy should include: `repo:generacclean/*:*`

### Already Configured ✅

- ✅ AWS Role: `claude-pr-review-ai-dev-oidc-role-neurio` exists
- ✅ Bedrock Access: Claude models enabled in us-east-1
- ✅ GitHub Action: `neurio/github-action-workflows/claude-pr-review@main` available
- ✅ Permissions: Configured in workflow YAML

---

## Customization

### Add Team Members

```bash
echo "newmember" >> .github/ai-review/reviewers.txt
git add .github/ai-review/reviewers.txt
git commit -m "chore: add newmember to AI review allowlist"
```

### Adjust Review Focus

Edit `.github/ai-review/review-prompt.md`:
- Change priority order
- Add new patterns to check
- Adjust rating threshold
- Change tone

### Adjust Turn Limit

Edit `.github/workflows/ai-code-review.yml`:
```yaml
max-turns: "60"  # Current (thorough)
max-turns: "40"  # Faster, cheaper
max-turns: "80"  # More thorough, expensive
```

---

## Troubleshooting Quick Reference

| Issue | Fix |
|-------|-----|
| Workflow not running | Check: PR to main, not draft, author on allowlist |
| AWS auth failure | Verify OIDC trust policy includes this repo |
| No comments posted | Check logs for "NO ISSUES FOUND" or rate limits |
| Action not found | Contact neurio org for access to action repo |

**Full troubleshooting**: See `AI_CODE_REVIEW_SETUP_GUIDE.md`

---

## Files Structure

```
.github/
├── ai-review/
│   ├── review-prompt.md      # Review instructions (edit to customize)
│   └── reviewers.txt          # Team allowlist (edit to add members)
└── workflows/
    ├── ai-code-review.yml     # Automatic review (PR open/sync)
    ├── claude-code-review.yml # Manual trigger (@claude mention)
    └── publish-github-packages.yml (existing, not modified)

AI_CODE_REVIEW_SETUP_GUIDE.md  # Complete setup documentation
AI_REVIEW_BOT_SUMMARY.md       # This file (quick reference)
```

---

## Key Points

1. ✅ **Branch created**: `feature/ai-code-review-bot` from `main`
2. ✅ **Files ready**: 5 new files (4 config + 1 guide)
3. ⚠️ **Not committed yet**: Review first, then commit when ready
4. ⚠️ **AWS verification needed**: Confirm OIDC trust policy
5. ✅ **Based on working setup**: Copied from installer-app
6. ✅ **Tailored for this repo**: Focus on native code, binary handling, mTLS

---

**Status**: Ready to commit and push  
**Next**: Review files → Commit → Push → Create PR → Test
