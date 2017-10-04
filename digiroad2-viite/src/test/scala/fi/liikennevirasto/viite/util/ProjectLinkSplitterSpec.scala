package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.MaxSuravageToleranceToGeometry
import org.scalatest.{FunSuite, Matchers}

class ProjectLinkSplitterSpec extends FunSuite with Matchers {
  test("Intersection point for simple case") {
    ProjectLinkSplitter.intersectionPoint((Point(0.0, 0.0), Point(10.0, 0.0)), (Point(0.0, 1.0), Point(10.0, -1.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Intersection is not a point for parallel") {
    ProjectLinkSplitter.intersectionPoint((Point(10.0, 2.0), Point(0.0, 1.0)), (Point(0.0, 1.0), Point(10.0, 2.0))) should be (None)
  }

  test("Intersection point not found for parallel") {
    ProjectLinkSplitter.intersectionPoint((Point(0.0, 1.5), Point(10.0, 2.5)), (Point(0.0, 1.0), Point(10.0, 2.0))) should be (None)
  }

  test("Intersection point for vertical segment") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0, 10.0)), (Point(0.0, 1.0), Point(10.0, -1.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Intersection point for two vertical segments") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0, 10.0)), (Point(5.0, 0.0), Point(5.0, 5.0))) should be (None)
    ProjectLinkSplitter.intersectionPoint((Point(6.0, 0.0), Point(6.0, 10.0)), (Point(5.0, 0.0), Point(5.0, 5.0))) should be (None)
  }

  test("Intersection point for nearly vertical segments") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 0.0), Point(4.9995, 5.0))) should be (Some(Point(5.0, 0.0)))
  }

  test("Intersection point for equal nearly vertical segments") {
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 0.0), Point(5.0005, 5.0))) should be (Some(Point(5.0, 0.0)))
    ProjectLinkSplitter.intersectionPoint((Point(5.0, 0.0), Point(5.0005, 10.0)), (Point(5.0, 11.0), Point(5.0005, 15.0))) should be (None)
  }

  test("Geometry boundaries test") {
    val geom = Seq(Point(5.0, 0.0), Point(5.0, 10.0), Point(6.0, 16.0), Point(9.0, 20.0), Point(14.0, 18.0), Point(16.0, 6.0))
    val (left, right) = ProjectLinkSplitter.geometryToBoundaries(geom)
    left.size should be (geom.size)
    right.size should be (geom.size)
    left.zip(right).zip(geom).foreach{ case ((l, r), g) =>
      // Assume max 90 degree turn on test data: left and right corners are sqrt(2)*MaxSuravageTolerance away
      l.distance2DTo(g) should be >= MaxSuravageToleranceToGeometry
      l.distance2DTo(g) should be <= (MaxSuravageToleranceToGeometry * Math.sqrt(2.0))
      l.distance2DTo(g) should be (r.distance2DTo(g) +- 0.001)
    }
  }
}
