@import XCTest;
@import VadCutIOS;

@interface ObjectiveCBridgeTests : XCTestCase
@end

@implementation ObjectiveCBridgeTests

- (void)testManualPlanFactoriesAndSelectorsAreVisibleToObjectiveC {
  VDAudioRange *range = [[VDAudioRange alloc] initWithStartMilliseconds:1000
                                                        endMilliseconds:3000];
  VDManualTrimPlan *removePlan = [VDManualTrimPlan removeRanges:@[ range ]];
  VDManualTrimPlan *keepPlan = [VDManualTrimPlan keepRanges:@[ range ]];

  XCTAssertEqual(removePlan.ranges.count, 1);
  XCTAssertEqual(keepPlan.ranges.count, 1);
  XCTAssertEqual(removePlan.ranges.firstObject.durationMilliseconds, 2000);
  XCTAssertTrue([VadCutObjC respondsToSelector:
      @selector(trimWithInputURL:outputURL:configuration:progress:completion:)]);
  XCTAssertTrue([VadCutObjC respondsToSelector:
      @selector(trimWithInputURL:outputURL:configuration:manualPlan:progress:completion:)]);
}

@end
