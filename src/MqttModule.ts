import { NativeModules } from 'react-native';
import type { MqttCertificates } from './types';

/**
 * Native module interface - parameter order must match iOS/Android native implementations
 * Order: broker, clientId, certificates, sniHostname, brokerIp, brokerCommonName, isAdminUser, successCallback, errorCallback
 */
interface MqttModuleType {
  connect(
    broker: string,
    clientId: string,
    certificates: MqttCertificates,
    sniHostname: string | null,
    brokerIp: string | null,
    brokerCommonName: string | null,
    isAdminUser: boolean,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  disconnect(
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  cleanup(
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  subscribe(
    topic: string,
    qos: number,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  unsubscribe(
    topic: string,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  publish(
    topic: string,
    message: string | Uint8Array,
    qos: number,
    retained: boolean,
    successCallback: (message: string) => void,
    errorCallback: (error: string) => void
  ): void;

  isConnected(callback: (isConnected: boolean) => void): void;
}

const { MqttModule } = NativeModules;

if (!MqttModule) {
  throw new Error(
    'MqttModule native module not found. Make sure you have properly linked the native module and rebuilt your app.'
  );
}

export default MqttModule as MqttModuleType;
