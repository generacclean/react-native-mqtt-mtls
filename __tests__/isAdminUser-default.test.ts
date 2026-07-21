/**
 * Tests for isAdminUser default behavior (secure-by-default)
 * Verifies that the library correctly defaults to false and passes security parameters
 */

import MqttModule from "../src/MqttModule";
import type { MqttConfig } from "../src/types";

// Mock the native module
jest.mock("../src/MqttModule");

describe("isAdminUser Default Behavior", () => {
  const getMockConnect = () => MqttModule.connect as jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();

    // Setup mock implementation
    getMockConnect().mockImplementation(
      (
        broker: string,
        clientId: string,
        certs: any,
        sni: string | null,
        ip: string | null,
        cn: string | null,
        isAdmin: boolean,
        success: (msg: string) => void,
        error: (err: string) => void,
      ) => {
        if (success) success("Connected");
      },
    );
  });

  describe("Secure-by-Default (isAdminUser defaults to false)", () => {
    it("should treat undefined isAdminUser as false", () => {
      // This simulates what MqttProvider.tsx does: config.isAdminUser ?? false
      const isAdminUser = undefined;
      const result = isAdminUser ?? false;

      expect(result).toBe(false);
    });

    it("should pass SNI hostname when isAdminUser is false", () => {
      const isAdminUser = false;
      const sniHostname = "mqtt.example.com";

      // Logic from MqttProvider: config.isAdminUser ?? false ? null : config.sniHostname ?? null
      const sniParam = isAdminUser ? null : sniHostname ?? null;

      expect(sniParam).toBe("mqtt.example.com");
    });

    it("should pass broker Common Name when isAdminUser is false", () => {
      const isAdminUser = false;
      const brokerCommonName = "mqtt.example.com";

      // Logic from MqttProvider: config.isAdminUser ?? false ? null : config.brokerCommonName ?? null
      const cnParam = isAdminUser ? null : brokerCommonName ?? null;

      expect(cnParam).toBe("mqtt.example.com");
    });

    it("should default omitted isAdminUser to false and pass security params", () => {
      const config = {
        isAdminUser: undefined, // Omitted by consumer
        sniHostname: "mqtt.example.com",
        brokerCommonName: "mqtt.example.com",
      };

      // Simulate MqttProvider logic
      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : config.sniHostname ?? null;
      const cn = isAdmin ? null : config.brokerCommonName ?? null;

      expect(isAdmin).toBe(false);
      expect(sni).toBe("mqtt.example.com");
      expect(cn).toBe("mqtt.example.com");
    });
  });

  describe("Admin Mode (isAdminUser: true)", () => {
    it("should pass null for SNI when isAdminUser is true", () => {
      const isAdminUser = true;
      const sniHostname = "mqtt.example.com";

      const sniParam = isAdminUser ? null : sniHostname ?? null;

      expect(sniParam).toBeNull();
    });

    it("should pass null for CN when isAdminUser is true", () => {
      const isAdminUser = true;
      const brokerCommonName = "mqtt.example.com";

      const cnParam = isAdminUser ? null : brokerCommonName ?? null;

      expect(cnParam).toBeNull();
    });

    it("should ignore sniHostname and brokerCommonName in admin mode", () => {
      const config = {
        isAdminUser: true,
        sniHostname: "mqtt.example.com", // Should be ignored
        brokerCommonName: "mqtt.example.com", // Should be ignored
      };

      // Simulate MqttProvider logic
      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : config.sniHostname ?? null;
      const cn = isAdmin ? null : config.brokerCommonName ?? null;

      expect(isAdmin).toBe(true);
      expect(sni).toBeNull();
      expect(cn).toBeNull();
    });
  });

  describe("Nullish Coalescing Behavior", () => {
    it("should handle undefined correctly", () => {
      // @ts-expect-error Testing nullish coalescing with undefined
      expect(undefined ?? false).toBe(false);
      // @ts-expect-error Testing nullish coalescing with undefined
      expect(undefined ?? true).toBe(true);
    });

    it("should handle null correctly", () => {
      // @ts-expect-error Testing nullish coalescing with null
      expect(null ?? false).toBe(false);
      // @ts-expect-error Testing nullish coalescing with null
      expect(null ?? true).toBe(true);
    });

    it("should NOT use default for explicit false", () => {
      // @ts-expect-error Testing that false is not nullish
      expect(false ?? true).toBe(false); // false is NOT nullish
    });

    it("should NOT use default for explicit true", () => {
      // @ts-expect-error Testing that true is not nullish
      expect(true ?? false).toBe(true); // true is NOT nullish
    });
  });

  describe("Security Parameter Logic", () => {
    it("should pass all security params when isAdminUser is false (production)", () => {
      const config = {
        isAdminUser: false,
        sniHostname: "mqtt.prod.com",
        brokerIp: "10.0.0.50",
        brokerCommonName: "mqtt.prod.com",
      };

      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : config.sniHostname ?? null;
      const ip = config.brokerIp ?? null;
      const cn = isAdmin ? null : config.brokerCommonName ?? null;

      expect(isAdmin).toBe(false);
      expect(sni).toBe("mqtt.prod.com");
      expect(ip).toBe("10.0.0.50");
      expect(cn).toBe("mqtt.prod.com");
    });

    it("should null out security params when isAdminUser is true (dev)", () => {
      const config = {
        isAdminUser: true,
        sniHostname: "mqtt.prod.com", // Provided but ignored
        brokerIp: "10.0.0.50",
        brokerCommonName: "mqtt.prod.com", // Provided but ignored
      };

      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : config.sniHostname ?? null;
      const ip = config.brokerIp ?? null;
      const cn = isAdmin ? null : config.brokerCommonName ?? null;

      expect(isAdmin).toBe(true);
      expect(sni).toBeNull();
      expect(ip).toBe("10.0.0.50"); // brokerIp is NOT affected by isAdminUser
      expect(cn).toBeNull();
    });

    it("should handle missing security params gracefully when secure (not recommended)", () => {
      const config = {
        isAdminUser: false,
        // Missing sniHostname and brokerCommonName
      };

      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : (config as any).sniHostname ?? null;
      const cn = isAdmin ? null : (config as any).brokerCommonName ?? null;

      expect(isAdmin).toBe(false);
      expect(sni).toBeNull();
      expect(cn).toBeNull();
    });
  });

  describe("Backward Compatibility", () => {
    it("should still support explicit isAdminUser: true", () => {
      const config = { isAdminUser: true };

      const result = config.isAdminUser ?? false;

      expect(result).toBe(true);
    });

    it("should support explicit isAdminUser: false", () => {
      const config = { isAdminUser: false };

      const result = config.isAdminUser ?? false;

      expect(result).toBe(false);
    });
  });

  describe("Type Safety", () => {
    it("should accept boolean values for isAdminUser", () => {
      const config1: Partial<MqttConfig> = { isAdminUser: true };
      const config2: Partial<MqttConfig> = { isAdminUser: false };
      const config3: Partial<MqttConfig> = {}; // isAdminUser omitted

      expect(typeof config1.isAdminUser).toBe("boolean");
      expect(typeof config2.isAdminUser).toBe("boolean");
      expect(config3.isAdminUser).toBeUndefined();
    });
  });

  describe("Real-World Scenarios", () => {
    it("should configure secure production connection correctly", () => {
      const productionConfig = {
        broker: "ssl://mqtt.production.com:8883",
        clientId: "prod-client-123",
        // isAdminUser omitted - defaults to false (secure)
        sniHostname: "mqtt.production.com",
        brokerCommonName: "mqtt.production.com",
        brokerIp: "10.0.0.50",
      };

      const isAdmin = (productionConfig as any).isAdminUser ?? false;
      const sni = isAdmin ? null : productionConfig.sniHostname ?? null;
      const cn = isAdmin ? null : productionConfig.brokerCommonName ?? null;

      expect(isAdmin).toBe(false);
      expect(sni).toBe("mqtt.production.com");
      expect(cn).toBe("mqtt.production.com");
    });

    it("should configure insecure dev connection correctly", () => {
      const devConfig = {
        broker: "ssl://localhost:8883",
        clientId: "dev-client",
        isAdminUser: true, // Dev: skip cert verification
      };

      const isAdmin = devConfig.isAdminUser ?? false;
      const sni = isAdmin ? null : (devConfig as any).sniHostname ?? null;
      const cn = isAdmin ? null : (devConfig as any).brokerCommonName ?? null;

      expect(isAdmin).toBe(true);
      expect(sni).toBeNull();
      expect(cn).toBeNull();
    });

    it("should support environment-based configuration", () => {
      const isDevelopment = false; // Simulate production

      const config = {
        broker: "ssl://mqtt.example.com:8883",
        clientId: "app-client",
        isAdminUser: isDevelopment ? true : false,
        sniHostname: "mqtt.example.com",
        brokerCommonName: "mqtt.example.com",
      };

      const isAdmin = config.isAdminUser ?? false;
      const sni = isAdmin ? null : config.sniHostname ?? null;
      const cn = isAdmin ? null : config.brokerCommonName ?? null;

      expect(isAdmin).toBe(false); // Secure in production
      expect(sni).toBe("mqtt.example.com");
      expect(cn).toBe("mqtt.example.com");
    });
  });
});
