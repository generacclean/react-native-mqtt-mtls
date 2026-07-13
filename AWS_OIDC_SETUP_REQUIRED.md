# AWS OIDC Setup Required

## Current Issue

The AI code review workflows are failing with:
```
Error: Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity
```

## Root Cause

The AWS IAM role `arn:aws:iam::017820692424:role/claude-pr-review-ai-dev-oidc-role-neurio` currently has an OIDC trust policy that only allows repos from the `neurio/*` organization.

Our repo `generacclean/react-native-mqtt-mtls` is not included in the trust policy.

## What Needs to Be Done

The AWS IAM role's trust policy needs to be updated to include `generacclean/react-native-mqtt-mtls`.

### Current Trust Policy (Assumed)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::017820692424:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:neurio/*:*"
        }
      }
    }
  ]
}
```

### Required Trust Policy Update

Add `generacclean/react-native-mqtt-mtls` to the trust policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::017820692424:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:neurio/*:*",
            "repo:generacclean/react-native-mqtt-mtls:*"
          ]
        }
      }
    }
  ]
}
```

## How to Update

1. **Log into AWS Console** (account 017820692424)
2. **Navigate to IAM** → Roles
3. **Search for role**: `claude-pr-review-ai-dev-oidc-role-neurio`
4. **Click on the role** → Trust relationships tab
5. **Click "Edit trust policy"**
6. **Add the new repo** to the `StringLike` condition (see above)
7. **Review changes** and click "Update policy"

## Verification

After updating the trust policy:

1. Go to PR: https://github.com/generacclean/react-native-mqtt-mtls/pull/5
2. Re-run the failed workflow
3. Verify the "Configure AWS Credentials" step now succeeds

## Alternative: Use Anthropic Action Directly

If updating the OIDC trust policy is not possible, we can switch to using `anthropics/claude-code-action@v1` which uses API keys instead of AWS OIDC.

However, this would require:
- Setting up an Anthropic API key
- Adding it as a GitHub secret
- Higher costs (no AWS Bedrock pricing)
- Diverging from the installer-app pattern

**Recommendation**: Update the OIDC trust policy to match installer-app's proven setup.

## Who Can Help

Contact whoever manages the AWS IAM roles for the AI dev account (017820692424). They likely set up the original role for the installer-app.

## Status

- ⚠️ **Blocked**: Waiting for AWS OIDC trust policy update
- 📋 **Repo**: `generacclean/react-native-mqtt-mtls`
- 🔑 **Role**: `claude-pr-review-ai-dev-oidc-role-neurio`
- 🆔 **Account**: 017820692424
