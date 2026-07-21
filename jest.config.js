module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  globals: {
    'process.env.NODE_ENV': 'test',
  },
  roots: ['<rootDir>/src', '<rootDir>/__tests__'],
  testMatch: ['**/__tests__/**/*.test.ts', '**/__tests__/**/*.test.tsx'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json'],
  moduleNameMapper: {
    '^react-native$': '<rootDir>/__mocks__/react-native.js',
  },
  collectCoverageFrom: [
    'src/**/*.{ts,tsx}',
    '!src/**/*.d.ts',
    '!src/MqttModule.ts', // Native module wrapper (tested via MqttManager)
    '!src/MqttProvider.tsx', // React component, tested separately
    '!src/MqttContext.ts', // React context definition
    '!src/useMqtt.ts', // React hook, tested separately
    '!src/index.tsx', // Re-export file
  ],
  coverageThreshold: {
    global: {
      branches: 70,
      functions: 65,
      lines: 75,
      statements: 75,
    },
  },
  globals: {
    'ts-jest': {
      tsconfig: {
        esModuleInterop: true,
        allowSyntheticDefaultImports: true,
      },
    },
  },
  setupFilesAfterEnv: ['<rootDir>/__tests__/setup.ts'],
};
