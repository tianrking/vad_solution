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

static TrimTask *RemoveKnownRanges(NSURL *inputURL, NSURL *outputURL) {
  VDTrimConfiguration *configuration = [VDTrimConfiguration new];
  VDManualTrimPlan *plan = [VDManualTrimPlan removeRanges:@[
    [[VDAudioRange alloc] initWithStartMilliseconds:10000 endMilliseconds:15000],
    [[VDAudioRange alloc] initWithStartMilliseconds:42000 endMilliseconds:44500],
  ]];
  return [VadCutObjC trimWithInputURL:inputURL
                            outputURL:outputURL
                         configuration:configuration
                              manualPlan:plan
                               progress:nil
                             completion:^(VDTrimResult *result, NSError *error) {
    if (error != nil) {
      NSLog(@"VadCut failed (%@/%ld): %@", error.domain, (long)error.code, error);
    } else {
      NSLog(@"Removed %lld ms in %@", result.removedDurationMilliseconds,
            result.removedRanges);
    }
  }];
}
