#import <VadCutIOS/VadCutIOS-Swift.h>

static TrimTask *TrimRecording(NSURL *inputURL, NSURL *outputURL) {
  VDTrimConfiguration *configuration = [VDTrimConfiguration new];
  configuration.minimumSilenceDurationMilliseconds = 700;
  return [VadCutObjC trimWithInputURL:inputURL
                            outputURL:outputURL
                         configuration:configuration
                               progress:^(NSInteger percent, NSString *phase) {
    NSLog(@"%@ %ld%%", phase, (long)percent);
  } completion:^(VDTrimResult *result, NSError *error) {
    if (error != nil) {
      NSLog(@"VadCut failed: %@", error);
    } else {
      NSLog(@"Output: %@", result.outputURL);
    }
  }];
}
