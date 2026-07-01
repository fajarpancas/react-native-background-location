#import "BackgroundLocation.h"
#import <Network/Network.h>
#import <UserNotifications/UserNotifications.h>

static NSString *const kDefaultsBaseURL      = @"vspirit_bl_baseURL";
static NSString *const kDefaultsHeader       = @"vspirit_bl_header";
static NSString *const kDefaultsParams       = @"vspirit_bl_additionalParams";
static NSString *const kDefaultsFailedCalls  = @"vspirit_bl_failedAPICalls";
static NSString *const kNotificationId       = @"VspiritLocationTracking";

@interface BackgroundLocation () <CLLocationManagerDelegate, UNUserNotificationCenterDelegate>

@property (strong, nonatomic) CLLocationManager *locationManager;
@property (strong, nonatomic) NSString *apiBaseURL;
@property (strong, nonatomic) NSDictionary *additionalParams;
@property (strong, nonatomic) NSString *header;
@property (strong, nonatomic) CLLocation *lastLocation;
@property (strong, nonatomic) NSTimer *locationUpdateTimer;
@property (strong, nonatomic) NSDate *lastAPICallTime;
@property (strong, nonatomic) NSMutableArray *failedAPICalls;
@property (nonatomic, assign) BOOL trackingStarted;
@property (nonatomic, assign) BOOL isNetworkAvailable;
@property (nonatomic, strong) id pathMonitor;
@property (nonatomic, copy) RCTPromiseResolveBlock permissionResolve;
@property (nonatomic, copy) RCTPromiseRejectBlock permissionReject;

@end

@implementation BackgroundLocation

RCT_EXPORT_MODULE();

- (instancetype)init {
    self = [super init];
    if (self) {
        _locationManager = [[CLLocationManager alloc] init];
        _locationManager.delegate = self;
        _locationManager.distanceFilter = kCLDistanceFilterNone;
        _locationManager.desiredAccuracy = kCLLocationAccuracyBest;

        if (@available(iOS 9.0, *)) {
            _locationManager.allowsBackgroundLocationUpdates = YES;
        }
        _locationManager.pausesLocationUpdatesAutomatically = NO;
        _locationManager.activityType = CLActivityTypeAutomotiveNavigation;

        if (@available(iOS 11.0, *)) {
            _locationManager.showsBackgroundLocationIndicator = YES;
        }

        _failedAPICalls = [NSMutableArray array];
        _trackingStarted = NO;
        _isNetworkAvailable = YES;

        [self loadSettings];
        [self setupNetworkMonitor];
    }
    return self;
}

- (void)setupNetworkMonitor {
    if (@available(iOS 12.0, *)) {
        nw_path_monitor_t monitor = nw_path_monitor_create();
        self.pathMonitor = (__bridge_transfer id)monitor;

        __weak typeof(self) weakSelf = self;
        nw_path_monitor_set_update_handler(monitor, ^(nw_path_t path) {
            BOOL available = nw_path_get_status(path) == nw_path_status_satisfied;
            weakSelf.isNetworkAvailable = available;
            if (available) {
                NSLog(@"[BackgroundLocation] Network available. Retrying failed calls...");
                dispatch_async(dispatch_get_main_queue(), ^{
                    [weakSelf retryFailedAPICalls];
                });
            }
        });
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND, 0));
        nw_path_monitor_start(monitor);
    }
}

RCT_EXPORT_METHOD(startTracking:(NSString *)baseURL header:(NSString *)header additionalParams:(NSDictionary *)params) {
    NSLog(@"[BackgroundLocation] startTracking baseURL: %@", baseURL);

    self.apiBaseURL = baseURL;
    self.header = header;
    self.additionalParams = params ?: @{};

    [self saveSettings];
    [self.locationManager startUpdatingLocation];
    [self.locationManager startMonitoringSignificantLocationChanges];
    [self startLocationUpdateTimer];

    self.trackingStarted = YES;
    [self showTrackingNotification:YES];
}

- (void)startLocationUpdateTimer {
    if (self.locationUpdateTimer) {
        [self.locationUpdateTimer invalidate];
    }
    self.locationUpdateTimer = [NSTimer scheduledTimerWithTimeInterval:5.0
                                                                target:self
                                                              selector:@selector(checkLocation)
                                                              userInfo:nil
                                                               repeats:YES];
    [[NSRunLoop currentRunLoop] addTimer:self.locationUpdateTimer forMode:NSRunLoopCommonModes];
}

RCT_EXPORT_METHOD(stopTracking) {
    [self.locationManager stopUpdatingLocation];
    [self.locationManager stopMonitoringSignificantLocationChanges];

    if (self.locationUpdateTimer) {
        [self.locationUpdateTimer invalidate];
        self.locationUpdateTimer = nil;
    }

    self.trackingStarted = NO;

    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    [center removeAllPendingNotificationRequests];
    [center removeAllDeliveredNotifications];
}

#pragma mark - Permissions

RCT_EXPORT_METHOD(requestPermissions:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    CLAuthorizationStatus status = [CLLocationManager authorizationStatus];

    if (status == kCLAuthorizationStatusAuthorizedAlways) {
        resolve(@(YES));
        return;
    }

    self.permissionResolve = resolve;
    self.permissionReject = reject;
    [self.locationManager requestAlwaysAuthorization];
}

RCT_EXPORT_METHOD(checkPermissions:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    CLAuthorizationStatus status = [CLLocationManager authorizationStatus];
    resolve(@(status == kCLAuthorizationStatusAuthorizedAlways));
}

- (void)locationManager:(CLLocationManager *)manager didChangeAuthorizationStatus:(CLAuthorizationStatus)status {
    [self handleAuthorizationStatus:status];
}

- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager {
    CLAuthorizationStatus status;
#if __has_feature(objc_generics) || defined(__IPHONE_14_0)
    if (@available(iOS 14.0, *)) {
        status = manager.authorizationStatus;
    } else {
        status = [CLLocationManager authorizationStatus];
    }
#else
    status = [CLLocationManager authorizationStatus];
#endif
    [self handleAuthorizationStatus:status];
}

- (void)handleAuthorizationStatus:(CLAuthorizationStatus)status {
    if (self.permissionResolve) {
        if (status == kCLAuthorizationStatusAuthorizedAlways) {
            self.permissionResolve(@(YES));
        } else if (status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted) {
            self.permissionResolve(@(NO));
        }
        self.permissionResolve = nil;
        self.permissionReject = nil;
    }
}

#pragma mark - Settings persistence

- (void)loadSettings {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    self.apiBaseURL = [defaults stringForKey:kDefaultsBaseURL];
    self.header = [defaults stringForKey:kDefaultsHeader];

    NSData *paramsData = [defaults objectForKey:kDefaultsParams];
    if (paramsData) {
        NSError *error;
        NSDictionary *dict = [NSKeyedUnarchiver unarchivedObjectOfClass:[NSDictionary class] fromData:paramsData error:&error];
        if (dict) {
            self.additionalParams = dict;
        }
    }

    NSData *failedData = [defaults objectForKey:kDefaultsFailedCalls];
    if (failedData) {
        NSError *error;
        NSSet *classes = [NSSet setWithObjects:[NSArray class], [NSDictionary class], [NSString class], nil];
        NSArray *calls = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:failedData error:&error];
        if ([calls isKindOfClass:[NSArray class]]) {
            self.failedAPICalls = [calls mutableCopy];
        }
    }
}

- (void)saveSettings {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    [defaults setObject:self.apiBaseURL forKey:kDefaultsBaseURL];
    [defaults setObject:self.header forKey:kDefaultsHeader];

    if (self.additionalParams) {
        NSError *error;
        NSData *paramsData = [NSKeyedArchiver archivedDataWithRootObject:self.additionalParams requiringSecureCoding:NO error:&error];
        if (paramsData) {
            [defaults setObject:paramsData forKey:kDefaultsParams];
        }
    }
    [defaults synchronize];
}

#pragma mark - CLLocationManagerDelegate

- (void)locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray<CLLocation *> *)locations {
    CLLocation *location = [locations lastObject];
    [self checkLocationWithLocation:location];
}

- (void)checkLocation {
    [self checkLocationWithLocation:self.locationManager.location];
}

- (void)checkLocationWithLocation:(CLLocation *)currentLocation {
    if (!currentLocation) return;

    if (self.lastLocation && [currentLocation distanceFromLocation:self.lastLocation] < 3.0) return;

    if (self.lastAPICallTime) {
        NSTimeInterval timeSinceLastCall = [[NSDate date] timeIntervalSinceDate:self.lastAPICallTime];
        if (timeSinceLastCall < 5.0) return;
    }

    [self sendLocationToAPIWithLatitude:currentLocation.coordinate.latitude
                              longitude:currentLocation.coordinate.longitude];

    [self sendEventWithName:@"LocationUpdated" body:@{
        @"latitude": @(currentLocation.coordinate.latitude),
        @"longitude": @(currentLocation.coordinate.longitude),
        @"timestamp": @([[NSDate date] timeIntervalSince1970])
    }];

    self.lastLocation = currentLocation;
    self.lastAPICallTime = [NSDate date];
}

#pragma mark - API

- (void)sendLocationToAPIWithLatitude:(double)latitude longitude:(double)longitude {
    NSDictionary *params = [self buildPayloadWithLatitude:latitude longitude:longitude];
    [self sendToAPIWithParams:params completion:^(BOOL success) {
        if (!success) {
            NSLog(@"[BackgroundLocation] Failed to send, storing for retry.");
        }
    }];
}

- (NSDictionary *)buildPayloadWithLatitude:(double)latitude longitude:(double)longitude {
    NSMutableDictionary *payload = [NSMutableDictionary dictionary];
    payload[@"latitude"]  = [NSString stringWithFormat:@"%f", latitude];
    payload[@"longitude"] = [NSString stringWithFormat:@"%f", longitude];

    if (self.additionalParams) {
        [payload addEntriesFromDictionary:self.additionalParams];
    }

    return payload;
}

- (void)sendToAPIWithParams:(NSDictionary *)params completion:(void (^)(BOOL success))completion {
    if (self.apiBaseURL.length == 0) {
        NSLog(@"[BackgroundLocation] API call skipped — no baseURL");
        if (completion) completion(NO);
        return;
    }

    NSURL *url = [NSURL URLWithString:self.apiBaseURL];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
    request.HTTPMethod = @"POST";
    [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    if (self.header) {
        [request setValue:self.header forHTTPHeaderField:@"Authorization"];
    }

    NSError *jsonError;
    NSData *httpBody = [NSJSONSerialization dataWithJSONObject:params options:0 error:&jsonError];
    if (jsonError || !httpBody) {
        NSLog(@"[BackgroundLocation] API call failed — JSON serialization error: %@", jsonError.localizedDescription);
        if (completion) completion(NO);
        return;
    }
    request.HTTPBody = httpBody;

    NSLog(@"[BackgroundLocation] REQUEST  POST %@  body: %@", self.apiBaseURL, [params description]);

    [[NSURLSession.sharedSession dataTaskWithRequest:request
                                  completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        if (error) {
            NSLog(@"[BackgroundLocation] RESPONSE  ERROR  %@", error.localizedDescription);
            [self storeFailedCall:params];
            if (completion) completion(NO);
            return;
        }
        NSInteger statusCode = [(NSHTTPURLResponse *)response statusCode];
        NSString *responseBody = data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : @"(empty)";
        NSLog(@"[BackgroundLocation] RESPONSE  %ld  body: %@", (long)statusCode, responseBody);
        BOOL ok = (statusCode == 200 || statusCode == 201);
        if (!ok) [self storeFailedCall:params];
        if (completion) completion(ok);
    }] resume];
}

- (void)storeFailedCall:(NSDictionary *)params {
    [self.failedAPICalls addObject:params];
    NSError *error;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:self.failedAPICalls requiringSecureCoding:NO error:&error];
    if (data) {
        [[NSUserDefaults standardUserDefaults] setObject:data forKey:kDefaultsFailedCalls];
        [[NSUserDefaults standardUserDefaults] synchronize];
    }
}

- (void)retryFailedAPICalls {
    NSArray *toRetry = [self.failedAPICalls copy];
    [self.failedAPICalls removeAllObjects];

    for (NSDictionary *params in toRetry) {
        [self sendToAPIWithParams:params completion:^(BOOL success) {
            if (!success) {
                [self.failedAPICalls addObject:params];
                NSError *error;
                NSData *data = [NSKeyedArchiver archivedDataWithRootObject:self.failedAPICalls requiringSecureCoding:NO error:&error];
                if (data) {
                    [[NSUserDefaults standardUserDefaults] setObject:data forKey:kDefaultsFailedCalls];
                    [[NSUserDefaults standardUserDefaults] synchronize];
                }
            }
        }];
    }
}

#pragma mark - Events

- (NSArray<NSString *> *)supportedEvents {
    return @[@"LocationUpdated"];
}

#pragma mark - Notification

- (void)showTrackingNotification:(BOOL)show {
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    if (!show) {
        [center removeAllPendingNotificationRequests];
        [center removeAllDeliveredNotifications];
        return;
    }

    center.delegate = self;
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL granted, NSError *error) {
        if (!granted) return;

        UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
        content.title = @"Location Tracking Active";
        content.body  = @"Your location is being tracked in the background";
        content.sound = nil;

        UNTimeIntervalNotificationTrigger *trigger =
            [UNTimeIntervalNotificationTrigger triggerWithTimeInterval:60 repeats:YES];

        UNNotificationRequest *req = [UNNotificationRequest requestWithIdentifier:kNotificationId
                                                                          content:content
                                                                          trigger:trigger];
        [center addNotificationRequest:req withCompletionHandler:nil];
    }];
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    if (@available(iOS 14.0, *)) {
        completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList);
    } else {
        completionHandler(UNNotificationPresentationOptionAlert);
    }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    if ([response.notification.request.identifier isEqualToString:kNotificationId]) {
        if (self.trackingStarted) {
            [self showTrackingNotification:YES];
        } else {
            [center removeAllPendingNotificationRequests];
            [center removeAllDeliveredNotifications];
        }
    }
    completionHandler();
}

#pragma mark - Lifecycle

- (void)dealloc {
    if (@available(iOS 12.0, *)) {
        if (self.pathMonitor) {
            nw_path_monitor_cancel((__bridge nw_path_monitor_t)self.pathMonitor);
        }
    }
}

@end
