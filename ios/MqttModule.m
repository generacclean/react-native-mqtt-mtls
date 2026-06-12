#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(MqttModule, RCTEventEmitter)

RCT_EXTERN_METHOD(connect:(NSString *)broker
                  clientId:(NSString *)clientId
                  certificates:(NSDictionary *)certificates
                  sniHostname:(NSString *)sniHostname
                  brokerIp:(NSString *)brokerIp
                  brokerCommonName:(NSString *)brokerCommonName
                  isAdminUser:(BOOL)isAdminUser
                  successCallback:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(disconnect:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(cleanup:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(subscribe:(NSString *)topic
                  qos:(NSInteger)qos
                  successCallback:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(unsubscribe:(NSString *)topic
                  successCallback:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(publish:(NSString *)topic
                  message:(NSString *)message
                  qos:(NSInteger)qos
                  retained:(BOOL)retained
                  successCallback:(RCTResponseSenderBlock)successCallback
                  errorCallback:(RCTResponseSenderBlock)errorCallback)

RCT_EXTERN_METHOD(isConnected:(RCTResponseSenderBlock)callback)

@end