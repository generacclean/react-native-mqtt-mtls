# GitHub App Installation Required

## Issue

The AI code review workflows are failing with:
```
Error: Claude Code is not installed on this repository. 
Please install the Claude Code GitHub App
```

## What's Needed

The **Claude Code GitHub App** needs to be granted access to the `generacclean/react-native-mqtt-mtls` repository.

## Who Can Do This

Someone with **organization admin access** to the `generacclean` GitHub organization.

## Installation Steps

### Option 1: Grant Access to Existing Installation (Recommended)

If Claude Code is already installed on the `generacclean` org (it's working on `generac-home-error-catalog`):

1. Go to: https://github.com/organizations/generacclean/settings/installations
2. Find **"Claude Code"** in the installed apps list
3. Click **"Configure"**
4. Under "Repository access", either:
   - Select **"All repositories"** (simplest)
   - Or select **"Only select repositories"** and add `react-native-mqtt-mtls`
5. Click **"Save"**

### Option 2: Install from Scratch

If Claude Code isn't installed on the org yet:

1. Go to: https://github.com/apps/claude-code
2. Click **"Install"**
3. Select the **`generacclean`** organization
4. Choose repository access:
   - **All repositories** (recommended for org-wide use)
   - Or select specific repos including `react-native-mqtt-mtls`
5. Click **"Install"**

## Verification

After installation:

1. Go to PR: https://github.com/generacclean/react-native-mqtt-mtls/pull/5
2. Go to **Actions** tab
3. Re-run the failed **"AI Code Review"** workflow
4. The "Run AI code review" step should now succeed

## Technical Details

- **Action**: `anthropics/claude-code-action@v1`
- **AWS Role**: `GitHubClaudeBedrockRoleGeneracClean` (already configured)
- **App URL**: https://github.com/apps/claude-code
- **Proven Working**: Same setup as `generac-home-error-catalog`

## Who to Ask

Contact whoever manages:
- The `generacclean` GitHub organization settings
- Or whoever set up the Claude Code integration for `generac-home-error-catalog`

They likely have the necessary admin access.

## Current Status

- ✅ Workflows configured correctly
- ✅ AWS OIDC role exists and is correct
- ⚠️ **Blocked**: Waiting for Claude Code GitHub App access
- 🎯 **Action Required**: Org admin needs to grant app access to this repo
