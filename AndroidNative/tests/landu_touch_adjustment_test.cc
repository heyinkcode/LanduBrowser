// Copyright 2026 The LanduBrowser Authors
// Use of this source code is governed by a BSD-style license.

#include "testing/gtest/include/gtest/gtest.h"
#include "third_party/blink/renderer/core/dom/document.h"
#include "third_party/blink/renderer/core/dom/element.h"
#include "third_party/blink/renderer/core/frame/local_frame.h"
#include "third_party/blink/renderer/core/frame/local_frame_view.h"
#include "third_party/blink/renderer/core/input/event_handler.h"
#include "third_party/blink/renderer/core/layout/hit_test_location.h"
#include "third_party/blink/renderer/core/layout/hit_test_request.h"
#include "third_party/blink/renderer/core/layout/hit_test_result.h"
#include "third_party/blink/renderer/core/page/touch_adjustment.h"
#include "third_party/blink/renderer/core/testing/core_unit_test_helper.h"

namespace blink {

// These tests call the production DOM hit test and candidate-selection methods.
// They intentionally leave the production touch algorithm unchanged initially.
class LanduTouchAdjustmentTest : public RenderingTest {
 protected:
  EventHandler& Handler() { return GetDocument().GetFrame()->GetEventHandler(); }

  HitTestLocation Region(float x, float y, int width = 21, int height = 21) {
    return HitTestLocation(PhysicalRect(
        PhysicalOffset(LayoutUnit(x), LayoutUnit(y)), PhysicalSize(width, height)));
  }

  HitTestResult Hit(const HitTestLocation& location) {
    return Handler().HitTestResultAtLocation(
        location, HitTestRequest::kReadOnly | HitTestRequest::kListBased);
  }

  bool Best(const HitTestLocation& location,
            const HitTestResult& result,
            gfx::Point& point,
            Node*& node) {
    return Handler().BestNodeForHitTestResult(
        TouchAdjustmentCandidateType::kClickable, location, result, point, node);
  }

  bool Inside(const HitTestLocation& location, const gfx::Point& point) {
    return location.ContainsPoint(gfx::PointF(
        GetDocument().GetFrame()->View()->ConvertFromRootFrame(point)));
  }
};

TEST_F(LanduTouchAdjustmentTest, RejectsPointOnlyInIntegerEnvelope) {
  SetBodyInnerHTML(R"HTML(
    <style>body { margin: 0; } a { position: absolute; display: block;
      left: 10px; top: 10px; width: 1px; height: 1px; }</style>
    <a id="target" href="#"></a>
  )HTML");
  HitTestLocation location = Region(10.5f, 10.5f);
  HitTestResult result = Hit(location);
  ASSERT_TRUE(result.ListBasedTestResult().Contains(GetElementById("target")));
  gfx::Point point;
  Node* node = nullptr;
  EXPECT_FALSE(Best(location, result, point, node));
}

TEST_F(LanduTouchAdjustmentTest, PreservesValidFractionalContact) {
  SetBodyInnerHTML(R"HTML(
    <style>body { margin: 0; } a { position: absolute; display: block;
      left: 10px; top: 10px; width: 1px; height: 1px; }</style>
    <a id="target" href="#"></a>
  )HTML");
  HitTestLocation location = Region(9.5f, 9.5f);
  HitTestResult result = Hit(location);
  gfx::Point point;
  Node* node = nullptr;
  ASSERT_TRUE(Best(location, result, point, node));
  EXPECT_EQ(GetElementById("target"), node);
  EXPECT_TRUE(Inside(location, point));
}

TEST_F(LanduTouchAdjustmentTest, PreservesIntegerPointInNarrowBoundaryStrip) {
  SetBodyInnerHTML(R"HTML(
    <style>body { margin: 0; } a { position: absolute; display: block;
      left: 31px; top: 20px; width: 1px; height: 5px; }</style>
    <a id="target" href="#"></a>
  )HTML");
  HitTestLocation location = Region(10.25f, 10.25f);
  HitTestResult result = Hit(location);
  gfx::Point point;
  Node* node = nullptr;
  ASSERT_TRUE(Best(location, result, point, node));
  EXPECT_EQ(GetElementById("target"), node);
  EXPECT_EQ(31, point.x());
  EXPECT_TRUE(Inside(location, point));
}

TEST_F(LanduTouchAdjustmentTest, SelectsValidAlternativeToOutsideCandidate) {
  SetBodyInnerHTML(R"HTML(
    <style>body { margin: 0; } a { position: absolute; display: block; }
      #outside { left: 10px; top: 20px; width: 1px; height: 3px; }
      #inside { left: 31px; top: 31px; width: 1px; height: 1px; }</style>
    <a id="outside" href="#"></a><a id="inside" href="#"></a>
  )HTML");
  HitTestLocation location = Region(10.5f, 10.5f);
  HitTestResult result = Hit(location);
  ASSERT_TRUE(result.ListBasedTestResult().Contains(GetElementById("outside")));
  ASSERT_TRUE(result.ListBasedTestResult().Contains(GetElementById("inside")));
  gfx::Point point;
  Node* node = nullptr;
  ASSERT_TRUE(Best(location, result, point, node));
  EXPECT_EQ(GetElementById("inside"), node);
  EXPECT_TRUE(Inside(location, point));
}

TEST_F(LanduTouchAdjustmentTest, RejectsPointOutsideTransformedContact) {
  SetBodyInnerHTML(R"HTML(
    <style>body { margin: 0; } a { position: absolute; display: block;
      left: 10px; top: 10px; width: 1px; height: 1px; }</style>
    <a id="target" href="#"></a>
  )HTML");
  HitTestLocation location(
      gfx::PointF(21, 21),
      gfx::QuadF(gfx::PointF(10, 21), gfx::PointF(21, 10), gfx::PointF(32, 21),
                 gfx::PointF(21, 32)));
  HitTestResult result = Hit(location);
  gfx::Point point;
  Node* node = nullptr;
  if (Best(location, result, point, node)) {
    EXPECT_TRUE(Inside(location, point));
  }
}

}  // namespace blink
