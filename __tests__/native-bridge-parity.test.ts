/**
 * Guards the three hand-maintained halves of the MQTT bridge against each other.
 *
 * `RCT_EXTERN_MODULE` splits the iOS module in two: MqttModule.m declares the selectors React
 * Native looks up, MqttModule.swift implements them. Nothing checks that the two agree. When they
 * do not, React Native logs a warning and drops the method from the JS module, so the call site
 * silently takes whatever fallback path it has. `MqttModuleType` in src/types.ts is a third copy
 * of the same surface, and the Android module is a fourth.
 *
 * These tests read all four as text and compare the exported selectors, the method sets, and the
 * argument counts, so none of them can drift alone.
 */

import { readFileSync } from "fs";
import { join } from "path";

const ROOT = join(__dirname, "..");
const OBJC_DECLARATIONS = join(ROOT, "ios", "MqttModule.m");
const SWIFT_IMPLEMENTATION = join(ROOT, "ios", "MqttModule.swift");
const TYPES_MODULE = join(ROOT, "src", "types.ts");
const ANDROID_MODULE = join(
  ROOT,
  "android",
  "src",
  "main",
  "java",
  "com",
  "reactnativemqttmtls",
  "MqttModule.java",
);

/**
 * The bridge surface, listed rather than derived. Every parser below is checked against this, so a
 * method that disappears from one of the four files is a failure instead of a shrunken test run.
 */
const BRIDGE_METHODS = [
  "cleanup",
  "connect",
  "disconnect",
  "isConnected",
  "publish",
  "subscribe",
  "unsubscribe",
] as const;

/**
 * Android takes three more arguments than iOS for `connect` — `keystorePath`, `keystorePassword`
 * and `keystoreFormat` — because it builds a keystore where iOS reads the Keychain. That is the one
 * deliberate difference, and MqttManager branches on `Platform.OS` to feed it.
 */
const ANDROID_EXTRA_ARGUMENTS: Record<string, number> = {
  connect: 3,
};

/** A Swift parameter of one of these types only makes sense on the bridge. */
const BRIDGE_CALLBACK_TYPE =
  /RCTResponseSenderBlock|RCTPromise(?:Resolve|Reject)Block/;

/**
 * Returns the text between the bracket at `openIndex` and its matching close bracket. Argument
 * types such as `(NSString *)` and `(message: string) => void` nest, so a regex cannot find the
 * end of the list.
 */
function readBalanced(
  source: string,
  openIndex: number,
  open = "(",
  close = ")",
): string {
  let depth = 0;

  for (let i = openIndex; i < source.length; i += 1) {
    if (source[i] === open) {
      depth += 1;
    } else if (source[i] === close) {
      depth -= 1;
      if (depth === 0) {
        return source.slice(openIndex + 1, i);
      }
    }
  }

  throw new Error(`Unbalanced '${open}' at index ${openIndex}`);
}

/**
 * Splits an argument list on the commas that separate arguments, ignoring commas nested inside a
 * type. `<` and `>` are deliberately not tracked, so the `>` of a `=>` return type is ignored.
 */
function splitArguments(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";

  for (const character of text) {
    if ("([{".includes(character)) {
      depth += 1;
    } else if (")]}".includes(character)) {
      depth -= 1;
    }

    if (character === "," && depth === 0) {
      parts.push(current);
      current = "";
    } else {
      current += character;
    }
  }

  parts.push(current);

  return parts.map((part) => part.trim()).filter((part) => part.length > 0);
}

/** Removes block and line comments so a doc comment cannot look like a declaration. */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
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

    if (labels.length === 0) {
      // A method declared with no arguments exports its bare name as the selector.
      const bare = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(args);

      if (!bare) {
        throw new Error(
          `Cannot parse RCT_EXTERN_METHOD(${args.trim()}) in ios/MqttModule.m`,
        );
      }

      selectors.set(bare[1], bare[1]);
      continue;
    }

    selectors.set(labels[0], labels.map((label) => `${label}:`).join(""));
  }

  return selectors;
}

/**
 * Maps every bridged `@objc func` in MqttModule.swift to the selector Swift exports for it.
 *
 * A method counts as bridged when MqttModule.m declares it or when it takes a bridge callback.
 * Anything else is an internal `@objc` helper — a timer target, a notification observer — that JS
 * never sees, so its parameter labels are its own business and this test skips it.
 */
function parseImplementedSelectors(
  swiftSource: string,
  declared: Map<string, string>,
): Map<string, string> {
  const selectors = new Map<string, string>();
  const declaration =
    /@objc\s*(?:\([^)]*\))?\s*(?:override\s+)?func\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = declaration.exec(swiftSource)) !== null) {
    const funcName = match[1];
    const argumentSource = readBalanced(swiftSource, declaration.lastIndex - 1);

    if (
      !declared.has(funcName) &&
      !BRIDGE_CALLBACK_TYPE.test(argumentSource)
    ) {
      continue;
    }

    const params = splitArguments(argumentSource).map((param) =>
      param.split(":")[0].trim().split(/\s+/),
    );

    if (params.length === 0) {
      selectors.set(funcName, funcName);
      continue;
    }

    // Swift renames a first parameter that carries a label, so `func f(bar:)` exports `fWithBar:`
    // and no longer matches the declaration. Every bridged method must use `_`.
    if (params[0][0] !== "_") {
      throw new Error(
        `@objc func ${funcName} labels its first parameter '${params[0][0]}'. Swift then exports ` +
          `a different selector than the RCT_EXTERN_METHOD in ios/MqttModule.m declares, and ` +
          `React Native drops the method from the JS module. Use '_' for the first parameter.`,
      );
    }

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
    const args = splitArguments(
      readBalanced(javaSource, declaration.lastIndex - 1),
    );

    counts.set(match[1], args.length);
  }

  return counts;
}

/**
 * Maps every method on the `MqttModuleType` interface to the argument count of each of its
 * overloads. `connect` is declared twice, once per platform, so it maps to two counts.
 */
function parseInterfaceArgumentCounts(
  typesSource: string,
): Map<string, number[]> {
  const source = stripComments(typesSource);
  const header = /export\s+interface\s+MqttModuleType\s*\{/.exec(source);

  if (!header) {
    throw new Error("No `export interface MqttModuleType` in src/types.ts");
  }

  const body = readBalanced(
    source,
    header.index + header[0].length - 1,
    "{",
    "}",
  );
  const overloads = new Map<string, number[]>();
  const signature = /([A-Za-z_][A-Za-z0-9_]*)\s*\??\s*\(/g;
  let match: RegExpExecArray | null;

  while ((match = signature.exec(body)) !== null) {
    const argumentSource = readBalanced(body, signature.lastIndex - 1);
    const counts = overloads.get(match[1]) ?? [];

    counts.push(splitArguments(argumentSource).length);
    overloads.set(match[1], counts);
    // Skip past the argument list so a callback type inside it is not read as a method.
    signature.lastIndex += argumentSource.length + 1;
  }

  return overloads;
}

// Parsed in `beforeAll` rather than at module scope. A parser that throws during module evaluation
// makes Jest report `Tests: 0 total`, which silently deletes every guard below instead of failing.
let declared: Map<string, string>;
let implemented: Map<string, string>;
let androidArgumentCounts: Map<string, number>;
let interfaceArgumentCounts: Map<string, number[]>;

beforeAll(() => {
  declared = parseDeclaredSelectors(readFileSync(OBJC_DECLARATIONS, "utf8"));
  implemented = parseImplementedSelectors(
    readFileSync(SWIFT_IMPLEMENTATION, "utf8"),
    declared,
  );
  androidArgumentCounts = parseAndroidArgumentCounts(
    readFileSync(ANDROID_MODULE, "utf8"),
  );
  interfaceArgumentCounts = parseInterfaceArgumentCounts(
    readFileSync(TYPES_MODULE, "utf8"),
  );
});

describe("iOS bridge declaration", () => {
  it("declares exactly the bridge methods", () => {
    expect([...declared.keys()].sort()).toEqual([...BRIDGE_METHODS]);
  });

  it.each(BRIDGE_METHODS)("exports a Swift implementation of `%s`", (name) => {
    expect(implemented.get(name)).toBe(declared.get(name));
  });

  /**
   * The check above runs from the declarations, so a Swift method that takes bridge callbacks but
   * has no `RCT_EXTERN_METHOD` would be invisible to it — and invisible to JS, which is the same
   * symptom. This closes that direction.
   */
  it("declares every bridged Swift method", () => {
    expect([...implemented.keys()].sort()).toEqual([...BRIDGE_METHODS]);
  });
});

describe("cross-platform bridge", () => {
  it.each(BRIDGE_METHODS)(
    "takes the argument count Android expects for `%s`",
    (name) => {
      const iosArgumentCount =
        (declared.get(name) as string).split(":").length - 1;
      const expected = iosArgumentCount + (ANDROID_EXTRA_ARGUMENTS[name] ?? 0);

      expect(androidArgumentCounts.get(name)).toBe(expected);
    },
  );
});

/**
 * `MqttModuleType` is what callers compile against, so a method missing from it is a method they
 * cannot call even when both native modules implement it. That is exactly how `cleanup` went
 * missing on iOS.
 */
describe("MqttModuleType interface", () => {
  it("declares exactly the bridge methods", () => {
    expect([...interfaceArgumentCounts.keys()].sort()).toEqual([
      ...BRIDGE_METHODS,
    ]);
  });

  it.each(BRIDGE_METHODS)("covers both platforms for `%s`", (name) => {
    const iosArgumentCount =
      (declared.get(name) as string).split(":").length - 1;
    const overloads = interfaceArgumentCounts.get(name) as number[];

    // One overload per distinct native argument count, so neither call site is a type error.
    expect(overloads).toContain(iosArgumentCount);
    expect(overloads).toContain(androidArgumentCounts.get(name));
  });
});
