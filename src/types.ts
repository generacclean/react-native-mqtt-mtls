export interface MqttMessage {
  topic: string;
  message: string | Uint8Array;
  qos: number;
  isBinary?: boolean;
  /**
   * True when the broker replayed this from its retained store on subscribe rather than delivering
   * it live, so it may predate the current session. Undefined on library versions that predate it.
   */
  isRetained?: boolean;
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
   *
   * The two platforms use this value differently, so it is not a cross-platform hostname check:
   * - Android matches it against the broker certificate's subjectAltName entries (DNS and
   *   iPAddress, exact match, no wildcards), so connecting by IP requires that IP in the SAN list.
   *   It is not sent as the TLS SNI extension.
   * - iOS sends it as the announced SNI hostname (`kCFStreamSSLPeerName`) only. Trust evaluation
   *   uses `SecPolicyCreateSSL(true, nil)` with no hostname, so no SAN match is performed and
   *   `brokerCommonName` is the only iOS identity pin.
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
   * Relative paths and the default are resolved against the app's no-backup directory first
   * (where react-native-ecc-csr 1.4.0+ stores the keystore), then the files directory. An
   * absolute path is used as-is when it exists; if it does not, the same filename is retried in
   * both directories, so a path persisted before the ecc-csr no-backup migration still resolves.
   * Either way the resolved path must stay inside app-private storage.
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
