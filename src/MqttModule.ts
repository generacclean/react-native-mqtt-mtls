import { NativeModules } from "react-native";
import type { MqttCertificates } from "./types";

/**
 * Native module interface - parameter order must match iOS/Android native implementations
 * iOS: broker, clientId, certificates, sniHostname, brokerIp, brokerCommonName, isAdminUser, successCallback, errorCallback
 * Android: broker, clientId, certificates, sniHostname, brokerIp, brokerCommonName, isAdminUser, keystorePath, keystorePassword, keystoreFormat, successCallback, errorCallback
 */
interface MqttModuleType {
  // iOS signature (7 params + 2 callbacks)
  connect(
    broker: string,
    clientId: string,
    certificates: MqttCertificates,
    sniHostname: string | null,
    brokerIp: string | null,
    brokerCommonName: string | null,
    isAdminUser: boolean,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  // Android signature (10 params + 2 callbacks) - overload
  connect(
    broker: string,
    clientId: string,
    certificates: MqttCertificates,
    sniHostname: string | null,
    brokerIp: string | null,
    brokerCommonName: string | null,
    isAdminUser: boolean,
    keystorePath: string | null,
    keystorePassword: string | null,
    keystoreFormat: string | null,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  disconnect(
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  cleanup(
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  subscribe(
    topic: string,
    qos: number,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  unsubscribe(
    topic: string,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  publish(
    topic: string,
    message: string | Uint8Array,
    qos: number,
    retained: boolean,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void,
  ): void;

  isConnected(callback: (isConnected: boolean) => void): void;
}

const { MqttModule } = NativeModules;

// In test environment, use empty object if native module not found (jest mock will override)
// Otherwise throw if module is missing
let mqttModuleInstance: MqttModuleType;

if (!MqttModule) {
  if (process.env.NODE_ENV === "test" || typeof jest !== "undefined") {
    // Empty object - jest mock will replace this
    mqttModuleInstance = {} as MqttModuleType;
  } else {
    throw new Error(
      "MqttModule native module not found. Make sure you have properly linked the native module and rebuilt your app.",
    );
  }
} else {
  mqttModuleInstance = MqttModule as MqttModuleType;
}

export default mqttModuleInstance;
