# AI Review Bot - Enhancements from installer-app

**Based on**: Actual working implementation from `neurio/installer-app`  
**Date**: 2026-07-13  
**Status**: Enhanced with production-proven features

---

## 🎉 New Features Added

### 1. Deep Review Deep Review Review ⭐

**File**: `.github/workflows/deep-code-review.yml`

**What it does**: Spawns 5 specialized agents for comprehensive, multi-perspective review:

| Agent | Focus | Model | Speed |
|-------|-------|-------|-------|
| **intent-reviewer** | PR matches stated intent | Sonnet | Fast |
| **clarity-reviewer** | Code readability, naming | Sonnet | Fast |
| **robustness-reviewer** | Bugs, edge cases, memory leaks | Opus | Thorough |
| **consistency-reviewer** | Project patterns, callback guards | Sonnet | Fast |
| **completeness-reviewer** | Tests, docs, CHANGELOG | Haiku | Very Fast |

**Trigger**: Comment `/deep-review` on any PR

**Output**: Single comprehensive report with deduplicated findings grouped by severity

**Cost**: ~$3-8 per review (higher than single-agent but much more thorough)

**When to use**:
- Large/complex PRs touching native code
- Critical security/mTLS changes
- When you want multiple perspectives
- Before important releases

**Example**:
```
# On PR, post comment:
/deep-review

# Bot will:
# 1. Spawn 5 agents in parallel
# 2. Each reviews from their specialty
# 3. Merges findings (no duplicates)
# 4. Posts single report with Critical/Important tiers
```

---

### 2. Enhanced Manual Trigger Options

**File**: `.github/workflows/claude-code-review.yml` (updated)

**New trigger methods**:

#### Method 1: @claude mention (existing)
```
@claude please review this
```

#### Method 2: Label-based trigger (NEW)
Add `claude-code` label to PR → triggers review automatically

#### Method 3: PR review comment (NEW)
Post `@claude` in review feedback → triggers review

**Benefits**:
- More flexible triggering
- Can be automated by CI/CD
- Integrates with GitHub label workflows

**Example automation**:
```yaml
# Auto-label PRs with certain files changed
name: Auto-request Claude review
on:
  pull_request:
    paths:
      - 'ios/**'
      - 'android/**'
jobs:
  label:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/labeler@v4
        with:
          configuration-path: .github/labeler.yml
# In labeler.yml:
# claude-code:
#   - ios/**/*.swift
#   - android/**/*.java
```

---

### 3. Increased Turn Limits

**Changes**:
- **ai-code-review.yml**: 60 → **80 turns**
- **claude-code-review.yml**: (inherits from action, typically 30-40)
- **deep-code-review.yml**: **80 turns**

**Why**: Based on installer-app CES team experience:
> "30 turns is too low for larger PRs: the reviewer exhausts the budget mid-analysis and dies with error_max_turns before posting any comments (a full run costs ~$1 for zero review value). 80 gives real headroom for big diffs."

**Impact**:
- ✅ Fewer incomplete reviews
- ✅ Better handling of large native module PRs
- ⚠️ Slightly higher cost (~10-20% more)

---

### 4. Better Concurrency Control

**Added**:
```yaml
concurrency:
  group: claude-manual-review-${{ github.event.issue.number || github.event.pull_request.number }}
  cancel-in-progress: true
```

**Benefits**:
- Cancels previous review if new commits pushed
- Saves cost on rapid iteration cycles
- Prevents duplicate reviews from racing

---

### 5. Timeout Management

**Set explicit timeouts**:
- ai-code-review.yml: 20 minutes
- claude-code-review.yml: **25 minutes** (more headroom for manual deep dives)
- deep-code-review.yml: **30 minutes** (multi-agent needs more time)

**Why**: Prevents hanging workflows that consume runner minutes

---

## 📊 Feature Comparison

| Feature | Basic Setup | Enhanced (Now) | installer-app CES |
|---------|-------------|----------------|-------------------|
| Automatic review | ✅ | ✅ | ✅ |
| @claude trigger | ✅ | ✅ | ✅ |
| Label trigger | ❌ | ✅ | ✅ |
| Deep Review multi-agent | ❌ | ✅ | ✅ |
| Turn limit | 60 | **80** | **80** |
| Concurrency control | ✅ | ✅ (enhanced) | ✅ |
| Multiple trigger methods | 1 | **3** | 3 |
| Timeout management | ✅ | ✅ (tuned) | ✅ |

---

## 🔧 How to Use

### Standard Review (Automatic)

Works exactly as before - just open a PR from allowlisted author.

### Manual Review (Enhanced)

**Option 1: @claude mention** (quick)
```
@claude please review this
```

**Option 2: Label** (for automation)
1. Add `claude-code` label to PR
2. Review triggers automatically

**Option 3: Deep multi-agent review** (thorough)
```
/deep-review
```

### Choosing the Right Review

| Scenario | Use | Cost | Time |
|----------|-----|------|------|
| Small PR (<100 lines) | Automatic | $0.50 | ~5 min |
| Medium PR, quick check | @claude | $1-2 | ~10 min |
| Large PR, thorough review | /deep-review | $3-8 | ~15-20 min |
| Native code with edge cases | /deep-review | $3-8 | ~15-20 min |
| Pre-release critical check | /deep-review | $3-8 | ~15-20 min |

---

## 📝 What's Different from Basic Setup

### Files Added
1. `.github/workflows/deep-code-review.yml` - Multi-agent workflow

### Files Enhanced
2. `.github/workflows/ai-code-review.yml` - Increased turns to 80
3. `.github/workflows/claude-code-review.yml` - Added label trigger, better concurrency

### No Changes Needed
- `.github/ai-review/review-prompt.md` - Already comprehensive
- `.github/ai-review/reviewers.txt` - Allowlist works for all modes

---

## 💰 Cost Analysis

### Per PR Estimates

**Automatic/Single-agent (@claude)**:
- Small PR (<50 lines): $0.10 - $0.30
- Medium PR (50-200 lines): $0.50 - $1.50
- Large PR (200-500 lines): $1.50 - $3.00

**Multi-agent (/deep-review)**:
- Small PR: $1.00 - $2.00 (not recommended, overkill)
- Medium PR: $2.00 - $4.00
- Large PR: $4.00 - $8.00
- Very large PR: $8.00 - $12.00

### Monthly Estimates (4-person allowlist)

**Conservative** (mostly automatic):
- 20 PRs/month automatic: $10-30
- 2 manual @claude: $2-4
- 1 bluemarlin: $4-8
- **Total**: ~$16-42/month

**Active** (frequent manual reviews):
- 30 PRs/month automatic: $15-45
- 8 manual @claude: $8-16
- 3 bluemarlin: $12-24
- **Total**: ~$35-85/month

**Reality from installer-app**: CES team runs ~$30-50/month with similar setup

---

## 🚀 Migration from Basic Setup

If you already have the basic setup:

```bash
# 1. Add bluemarlin workflow
git add .github/workflows/deep-code-review.yml

# 2. Update existing workflows (already done in feature branch)
git add .github/workflows/ai-code-review.yml
git add .github/workflows/claude-code-review.yml

# 3. Commit
git commit -m "feat: add Deep Review multi-agent review and enhance manual triggers

- Add /deep-review for comprehensive multi-agent analysis
- Increase turn limits to 80 (proven effective in installer-app)
- Add label trigger (claude-code) for manual reviews
- Improve concurrency control and timeout management"
```

---

## 🧪 Testing the New Features

### Test 1: Deep Review Deep Review Review

1. Create a test PR with substantial changes (100+ lines)
2. Comment: `/deep-review`
3. Check Actions tab - should see "Deep Review Code Review" workflow
4. Wait ~15-20 minutes
5. Check for comprehensive report with multiple perspectives

**Expected output**:
```markdown
_🤖 Deep Review multi-agent code review. Five specialized agents analyzed this PR._

## Overview
[Summary from all agents]

## Critical Issues (91-100)
[Findings that all agents agree are critical]

## Important Issues (76-90)
[Important findings from multiple agents]

## Overall Assessment
[Merged recommendation]
```

### Test 2: Label Trigger

1. Create a test PR
2. Add `claude-code` label via GitHub UI
3. Check Actions tab - should see "Claude Code Review (Manual)" workflow
4. Verify review is posted

### Test 3: Higher Turn Limits

1. Create a large PR (300+ lines, complex native changes)
2. Let automatic review run
3. Check workflow logs - should NOT hit `error_max_turns`
4. Verify complete review is posted

---

## 📚 References

### Learned from installer-app

- **CES workflow**: `.github/workflows/ces-ai-code-review.yml`
- **Deep Review**: `.github/workflows/deep-code-review.yml`
- **Manual trigger**: `.github/workflows/claude-code-review.yml`
- **Turn limit insight**: 80 turns prevents mid-review exhaustion

### Key Patterns Adopted

1. **Multiple trigger methods** - @claude, label, PR review comment
2. **Turn limit tuning** - 80 turns for complex reviews
3. **Multi-agent architecture** - Specialized agents for different dimensions
4. **Concurrency control** - Cancel-in-progress for cost savings
5. **Timeout management** - Different limits for different review depths

---

## 🎯 Best Practices (from installer-app experience)

1. **Use automatic for most PRs** - It's cheap and catches common issues
2. **Use @claude for quick manual checks** - When you want a second opinion
3. **Use /deep-review for critical changes**:
   - Native module changes
   - Security/mTLS changes
   - Large refactors
   - Pre-release verification

4. **Don't overuse bluemarlin** - It's thorough but expensive:
   - ✅ Good: 1-2 times per week on important PRs
   - ❌ Bad: Every PR (use automatic instead)

5. **Monitor costs** - Check AWS Bedrock billing weekly for first month

6. **Iterate on prompts** - installer-app tuned their prompts over months

7. **Label automation** - Set up auto-labeling for native file changes

---

## 🔮 Future Enhancements (Optional)

Based on installer-app's full setup:

### 1. Slack Notifications
```yaml
- name: Notify Slack
  if: always()
  uses: slackapi/slack-github-action@v1
  with:
    webhook-url: ${{ secrets.SLACK_WEBHOOK }}
```

### 2. Cost Tracking
Add step to log estimated cost to PR comment

### 3. Custom Agent Definitions
Create `.claude/plugins/bluemarlin/agents/` with library-specific agents

### 4. Integration Tests
Add workflow that runs on review completion to verify findings

---

## ✅ Summary

### What We Added
- ✅ Deep Review multi-agent review workflow
- ✅ Label trigger (claude-code) for manual reviews
- ✅ Increased turn limits (60 → 80)
- ✅ Enhanced concurrency control
- ✅ Better timeout management
- ✅ Multiple trigger methods

### What Stayed the Same
- ✅ Automatic review workflow (just better)
- ✅ @claude trigger (just more options)
- ✅ Review prompt (already good)
- ✅ Allowlist mechanism

### Cost Impact
- Automatic: No change ($0.50-$3.00 per PR)
- Manual @claude: No change ($1-3 per review)
- New bluemarlin: $3-8 per review (opt-in only)

### Estimated Monthly Cost
- **Conservative**: $16-42/month
- **Active**: $35-85/month
- **installer-app actual**: $30-50/month

---

**Status**: Enhanced and production-ready  
**Based on**: Real installer-app workflows (proven in production)  
**Ready**: Commit and test
