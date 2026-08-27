import { NativeModules } from "react-native";
import type { MqttModuleType } from "./types";

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
