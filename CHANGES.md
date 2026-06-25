# react-native-mqtt-mtls Library Changes

Branch: `fix/module-name-and-default-admin-user`

## Summary

Fixed two issues in the `@generacclean/react-native-mqtt-mtls` library:

1. **Module name in type definitions** - Changed from `"react-native-mqtt-mtls"` to `"@generacclean/react-native-mqtt-mtls"` to match the actual package name
2. **Default value for `isAdminUser`** - Changed from `false` to `true` as this is the expected behavior for Field Pro installers

## Changes Made

### 1. Fixed Module Declaration in `index.d.ts`

**Before:**

```typescript
declare module "react-native-mqtt-mtls" {
```

**After:**

```typescript
declare module "@generacclean/react-native-mqtt-mtls" {
```

This fixes the import resolution issue that required the workaround `.d.ts` file in the consumer app.

### 2. Updated `isAdminUser` Default to `true`

Changed in three files:

- `src/types.ts` - Added documentation
- `src/MqttProvider.tsx` - Changed `config.isAdminUser ?? false` to `config.isAdminUser ?? true`
- `src/MqttManager.ts` - Changed `config.isAdminUser ?? false` to `config.isAdminUser ?? true`

**Documentation Added:**

```typescript
/**
 * Whether to connect as an admin user with full permissions.
 * Defaults to true. Set to false only if SNI hostname verification is required.
 */
isAdminUser?: boolean;
```

### 3. Version Bump

- **Old version:** `1.0.0`
- **New version:** `1.1.0`

This is a minor version bump because:

- The default behavior change is backwards-compatible (anyone explicitly passing `isAdminUser: false` will still work)
- The module name fix is technically a breaking change for the type definitions but fixes a bug

## Files Changed

```
 index.d.ts           |  6 +++++-
 package.json         |  4 ++--
 src/MqttManager.ts   | 14 +++++++-------
 src/MqttProvider.tsx |  6 +++---
 src/types.ts         | 11 ++++++++++-
 5 files changed, 27 insertions(+), 14 deletions(-)
```

## Next Steps

After review:

1. Commit these changes
2. Push to remote
3. Create a PR for the library
4. Publish version 1.1.0 to GitHub Package Registry
5. Update `installer-app` to use version 1.1.0
6. Remove the workaround `types/react-native-mqtt-mtls.d.ts` file from `installer-app`
7. Remove `isAdminUser: true` from `installer-app` config (since it's now the default)

## Impact on installer-app

Once this library version is published and integrated:

1. **Can remove:** `installer-app/types/react-native-mqtt-mtls.d.ts` (workaround no longer needed)
2. **Can simplify:** Remove `isAdminUser: true` from `app/core/config/index.ts` since it's now the default
3. **Resolves:** PR #2975 review comment about the type definition workaround
