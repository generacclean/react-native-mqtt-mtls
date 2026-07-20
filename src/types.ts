export interface MqttMessage {
  topic: string;
  message: string | Uint8Array;
  qos: number;
  isBinary?: boolean;
}

export interface MqttCertificates {
  clientCert: string;
  privateKeyAlias: string;
  rootCa: string;
  /**
   * Hardware-backed keys are not supported for mTLS.
   * Hardware keys in AndroidKeyStore fail during TLS handshake because
   * Conscrypt requires extractable key material for ECDHE operations, but
   * hardware keys are non-extractable by design.
   *
   * Must be false or omitted. Throws error if true.
   * @default false
   */
  useHardwareKey?: boolean;
}

export interface MqttConfig {
  broker: string;
  clientId: string;
  /**
   * Whether to connect in admin mode, which disables certificate verification.
   *
   * SECURITY WARNING:
   * - When true: Skips SNI hostname verification and broker Common Name pinning
   * - When false (default): Enforces full certificate verification (production recommended)
   *
   * Admin mode should ONLY be used in development/testing environments or when
   * connecting to brokers with self-signed certificates. Production deployments
   * should use false (default) with proper sniHostname and brokerCommonName.
   *
   * @default false (secure-by-default)
   */
  isAdminUser?: boolean;
  /**
   * Expected SNI hostname for certificate verification.
   * Required when isAdminUser is false. Ignored when isAdminUser is true.
   */
  sniHostname?: string;
  /**
   * Direct IP address of the broker (optional).
   */
  brokerIp?: string;
  /**
   * Expected Common Name in the broker's certificate for pinning.
   * Required when isAdminUser is false. Ignored when isAdminUser is true.
   */
  brokerCommonName?: string;
  certificates: MqttCertificates;
  /**
   * (Android only) Path to the keystore file containing the client private key.
   * If not provided, defaults to 'software_keys.p12' for backward compatibility.
   * This makes the keystore location explicit in the API instead of a hidden filesystem convention.
   *
   * Note: iOS loads keys from Keychain using the privateKeyAlias - this parameter is ignored.
   */
  keystorePath?: string;
  /**
   * (Android only) Password for the keystore file.
   * If not provided, defaults to empty string for backward compatibility.
   *
   * Note: iOS loads keys from Keychain - this parameter is ignored.
   */
  keystorePassword?: string;
  /**
   * (Android only) Format of the keystore file.
   * - 'pkcs12': Standard PKCS#12 format (default)
   * - 'encrypted': Android EncryptedFile format (AES256-GCM-HKDF)
   * If not provided, will attempt encrypted first, then fall back to pkcs12.
   *
   * Note: iOS loads keys from Keychain - this parameter is ignored.
   */
  keystoreFormat?: 'pkcs12' | 'encrypted';
  onMessage?: (message: MqttMessage) => void;
  onConnect?: () => void;
  onConnectionLost?: (error: string) => void;
  onReconnect?: () => void;
  onError?: (error: string) => void;
}

export interface MqttContextType {
  isConnected: boolean;
  error: string | null;
  connect: (config: MqttConfig) => Promise<void>;
  disconnect: () => Promise<void>;
  subscribe: (topic: string, qos?: number) => Promise<void>;
  unsubscribe: (topic: string) => Promise<void>;
  publish: (
    topic: string,
    message: string,
    qos?: number,
    retained?: boolean,
  ) => Promise<void>;
}
