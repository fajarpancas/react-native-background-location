#import <Foundation/Foundation.h>
#import <CoreLocation/CoreLocation.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTBridgeModule.h>

@interface BackgroundLocation : RCTEventEmitter <RCTBridgeModule, CLLocationManagerDelegate>

- (void)startTracking:(NSString *)baseURL header:(NSString *)header additionalParams:(NSDictionary *)params;
- (void)stopTracking;
- (void)requestPermissions:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject;
- (void)checkPermissions:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject;

@end
