/**
 * Guards the iOS bridge declaration against its Swift implementation.
 *
 * `RCT_EXTERN_MODULE` splits the module in two: MqttModule.m declares the selectors React Native
 * looks up, MqttModule.swift implements them. Nothing checks that the two agree. When they do not,
 * React Native logs a warning and drops the method from the JS module, so the call site silently
 * takes whatever fallback path it has. These tests read both files as text and compare the
 * selectors, and compare argument counts against the Android module so the platforms stay in step.
 */

import { readFileSync } from "fs";
import { join } from "path";

const IOS_DIR = join(__dirname, "..", "ios");
const ANDROID_MODULE = join(
  __dirname,
  "..",
  "android",
  "src",
  "main",
  "java",
  "com",
  "reactnativemqttmtls",
  "MqttModule.java",
);

/**
 * Returns the text between the parenthesis at `openIndex` and its matching close parenthesis.
 * Argument types such as `(NSString *)` nest, so a regex cannot find the end of the list.
 */
function readBalanced(source: string, openIndex: number): string {
  let depth = 0;

  for (let i = openIndex; i < source.length; i += 1) {
    if (source[i] === "(") {
      depth += 1;
    } else if (source[i] === ")") {
      depth -= 1;
      if (depth === 0) {
        return source.slice(openIndex + 1, i);
      }
    }
  }

  throw new Error(`Unbalanced parenthesis at index ${openIndex}`);
}

/** Maps every `RCT_EXTERN_METHOD` in MqttModule.m to its Objective-C selector. */
function parseDeclaredSelectors(objcSource: string): Map<string, string> {
  const selectors = new Map<string, string>();
  const macro = /RCT_EXTERN_METHOD\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = macro.exec(objcSource)) !== null) {
    const args = readBalanced(objcSource, macro.lastIndex - 1);
    // Every argument reads `label:(Type)name`, so each `label:(` is one selector piece.
    const labels = [...args.matchAll(/([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\(/g)].map(
      (m) => m[1],
    );

    expect(labels.length).toBeGreaterThan(0);
    selectors.set(labels[0], labels.map((label) => `${label}:`).join(""));
  }

  return selectors;
}

/** Maps every `@objc func` in MqttModule.swift to the selector Swift exports for it. */
function parseImplementedSelectors(swiftSource: string): Map<string, string> {
  const selectors = new Map<string, string>();
  const declaration =
    /@objc\s*(?:\([^)]*\))?\s*(?:override\s+)?func\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = declaration.exec(swiftSource)) !== null) {
    const funcName = match[1];
    const params = readBalanced(swiftSource, declaration.lastIndex - 1)
      .split(",")
      .map((param) => param.split(":")[0].trim().split(/\s+/))
      .filter((parts) => parts[0].length > 0);

    if (params.length === 0) {
      selectors.set(funcName, funcName);
      continue;
    }

    // Swift renames a first parameter that carries a label to `funcNameWithLabel:`. Every method
    // here uses `_`, and the parser below assumes it, so fail loudly rather than compare a guess.
    expect(params[0][0]).toBe("_");

    const pieces = params.map((parts, index) =>
      index === 0 ? `${funcName}:` : `${parts[0]}:`,
    );
    selectors.set(funcName, pieces.join(""));
  }

  return selectors;
}

/** Maps every `@ReactMethod` in the Android module to its argument count. */
function parseAndroidArgumentCounts(javaSource: string): Map<string, number> {
  const counts = new Map<string, number>();
  const declaration =
    /@ReactMethod[\s\S]{0,120}?public\s+void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = declaration.exec(javaSource)) !== null) {
    const args = readBalanced(javaSource, declaration.lastIndex - 1)
      .split(",")
      .map((arg) => arg.trim())
      .filter((arg) => arg.length > 0);

    counts.set(match[1], args.length);
  }

  return counts;
}

const declared = parseDeclaredSelectors(
  readFileSync(join(IOS_DIR, "MqttModule.m"), "utf8"),
);
const implemented = parseImplementedSelectors(
  readFileSync(join(IOS_DIR, "MqttModule.swift"), "utf8"),
);
const androidArgumentCounts = parseAndroidArgumentCounts(
  readFileSync(ANDROID_MODULE, "utf8"),
);

const declaredNames = [...declared.keys()];

describe("iOS bridge declaration", () => {
  it("declares the methods the JS module expects", () => {
    expect(declaredNames.sort()).toEqual([
      "cleanup",
      "connect",
      "disconnect",
      "isConnected",
      "publish",
      "subscribe",
      "unsubscribe",
    ]);
  });

  it.each(declaredNames)("exports a Swift implementation of `%s`", (name) => {
    expect(implemented.get(name)).toBe(declared.get(name));
  });
});

/**
 * Android takes three more arguments than iOS for `connect` — `keystorePath`,
 * `keystorePassword` and `keystoreFormat` — because it builds a keystore where iOS reads the
 * Keychain. That is the one deliberate difference, and MqttManager branches on `Platform.OS` to
 * feed it. Every other method must line up, so the counts are listed rather than derived.
 */
const ANDROID_EXTRA_ARGUMENTS: Record<string, number> = {
  connect: 3,
};

describe("cross-platform bridge", () => {
  it.each(declaredNames)(
    "takes the argument count Android expects for `%s`",
    (name) => {
      const iosArgumentCount =
        (declared.get(name) as string).split(":").length - 1;
      const expected = iosArgumentCount + (ANDROID_EXTRA_ARGUMENTS[name] ?? 0);

      expect(androidArgumentCounts.get(name)).toBe(expected);
    },
  );
});
