package fi.liikennevirasto.digiroad2.linearasset.oracle

import fi.liikennevirasto.digiroad2.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{NumericValue, UnknownSpeedLimit, NewLimit, RoadLink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase._
import fi.liikennevirasto.digiroad2.util.TestTransactions
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.{FunSuite, Matchers}
import org.scalatest.mock.MockitoSugar
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{StaticQuery => Q}

import scala.concurrent.Promise

import scala.language.implicitConversions

class OracleSpeedLimitProviderSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockVVHClient = MockitoSugar.mock[VVHClient]
  val provider = new SpeedLimitService(new DummyEventBus, mockVVHClient, mockRoadLinkService) {
    override def withDynTransaction[T](f: => T): T = f
  }

  val roadLink = RoadLink(
    1l, List(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, Municipality, 1,
    TrafficDirection.UnknownDirection, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235)))
  when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(List(roadLink))
  when(mockRoadLinkService.getRoadLinksFromVVH(any[Int])).thenReturn(List(roadLink))
  when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((List(roadLink), Nil))
  when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[Int])).thenReturn((List(roadLink), Nil))

  when(mockVVHClient.fetchVVHRoadlinks(Set(362964704l, 362955345l, 362955339l)))
    .thenReturn(Seq(VVHRoadlink(362964704l, 91,  List(Point(0.0, 0.0), Point(117.318, 0.0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers),
                    VVHRoadlink(362955345l, 91,  List(Point(117.318, 0.0), Point(127.239, 0.0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers),
                    VVHRoadlink(362955339l, 91,  List(Point(127.239, 0.0), Point(146.9, 0.0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers)))

  private def runWithRollback(test: => Unit): Unit = TestTransactions.runWithRollback()(test)

  def passingMunicipalityValidation(code: Int): Unit = {}
  def failingMunicipalityValidation(code: Int): Unit = { throw new IllegalArgumentException }

  val roadLinkForSeparation = RoadLink(388562360, List(Point(0.0, 0.0), Point(0.0, 200.0)), 200.0, Municipality, 1, TrafficDirection.BothDirections, UnknownLinkType, None, None)
  when(mockRoadLinkService.getRoadLinkFromVVH(388562360l)).thenReturn(Some(roadLinkForSeparation))

  test("create new speed limit") {
    runWithRollback {
      val roadLink = VVHRoadlink(1l, 0, List(Point(0.0, 0.0), Point(0.0, 200.0)), Municipality, TrafficDirection.UnknownDirection, AllOthers)
      when(mockVVHClient.fetchVVHRoadlink(1l)).thenReturn(Some(roadLink))
      when(mockVVHClient.fetchVVHRoadlinks(Set(1l))).thenReturn(Seq(roadLink))

      val id = provider.create(Seq(NewLimit(1, 0.0, 150.0)), 30, "test", (_) => Unit)

      val createdLimit = provider.find(id.head).get
      createdLimit.value should equal(Some(NumericValue(30)))
      createdLimit.createdBy should equal(Some("test"))
    }
  }

  test("split existing speed limit") {
    runWithRollback {
      val roadLink = VVHRoadlink(388562360, 0, List(Point(0.0, 0.0), Point(0.0, 200.0)), Municipality, TrafficDirection.UnknownDirection, AllOthers)
      when(mockVVHClient.fetchVVHRoadlink(388562360l)).thenReturn(Some(roadLink))
      when(mockVVHClient.fetchVVHRoadlinks(Set(388562360l))).thenReturn(Seq(roadLink))
      val speedLimits = provider.split(200097, 100, 50, 60, "test", (_) => Unit)

      val existing = speedLimits.find(_.id == 200097).get
      val created = speedLimits.find(_.id != 200097).get
      existing.value should be(Some(NumericValue(50)))
      created.value should be(Some(NumericValue(60)))
    }
  }

  test("request unknown speed limit persist in bounding box fetch") {
    runWithRollback {
      val eventBus = MockitoSugar.mock[DigiroadEventBus]
      val provider = new SpeedLimitService(eventBus, mockVVHClient, mockRoadLinkService) {
        override def withDynTransaction[T](f: => T): T = f
      }

      provider.get(BoundingRectangle(Point(0.0, 0.0), Point(1.0, 1.0)), Set(235))

      verify(eventBus, times(1)).publish("speedLimits:persistUnknownLimits", Seq(UnknownSpeedLimit(1, 235, Municipality)))
    }
  }

  test("request unknown speed limit persist in municipality fetch") {
    runWithRollback {
      val eventBus = MockitoSugar.mock[DigiroadEventBus]
      val provider = new SpeedLimitService(eventBus, mockVVHClient, mockRoadLinkService) {
        override def withDynTransaction[T](f: => T): T = f
      }

      provider.get(235)

      verify(eventBus, times(1)).publish("speedLimits:persistUnknownLimits", Seq(UnknownSpeedLimit(1, 235, Municipality)))
    }
  }

  test("separate speed limit to two") {
    runWithRollback {
      val createdId = provider.separate(200097, 50, 40, "test", passingMunicipalityValidation).filter(_.id != 200097).head.id
      val createdLimit = provider.get(Seq(createdId)).head
      val oldLimit = provider.get(Seq(200097l)).head

      oldLimit.linkId should be (388562360)
      oldLimit.sideCode should be (SideCode.TowardsDigitizing)
      oldLimit.value should be (Some(NumericValue(50)))
      oldLimit.modifiedBy should be (Some("test"))

      createdLimit.linkId should be (388562360)
      createdLimit.sideCode should be (SideCode.AgainstDigitizing)
      createdLimit.value should be (Some(NumericValue(40)))
      createdLimit.createdBy should be (Some("test"))
    }
  }

  test("separation should call municipalityValidation") {
    runWithRollback {
      intercept[IllegalArgumentException] {
        provider.separate(200097, 50, 40, "test", failingMunicipalityValidation)
      }
    }
  }

  test("speed limit separation fails if no speed limit is found") {
    runWithRollback {
      intercept[NoSuchElementException] {
        provider.separate(0, 50, 40, "test", passingMunicipalityValidation)
      }
    }
  }

  test("speed limit separation fails if speed limit is one way") {
    val roadLink = RoadLink(1611445, List(Point(0.0, 0.0), Point(0.0, 200.0)), 200.0, Municipality, 1, TrafficDirection.BothDirections, UnknownLinkType, None, None)
    when(mockRoadLinkService.getRoadLinkFromVVH(1611445)).thenReturn(Some(roadLink))

    runWithRollback {
      intercept[IllegalArgumentException] {
        provider.separate(300388, 50, 40, "test", passingMunicipalityValidation)
      }
    }
  }

  test("speed limit separation fails if road link is one way") {
    val roadLink = RoadLink(1611388, List(Point(0.0, 0.0), Point(0.0, 200.0)), 200.0, Municipality, 1, TrafficDirection.TowardsDigitizing, UnknownLinkType, None, None)
    when(mockRoadLinkService.getRoadLinkFromVVH(1611388)).thenReturn(Some(roadLink))

    runWithRollback {
      intercept[IllegalArgumentException] {
        provider.separate(200299, 50, 40, "test", passingMunicipalityValidation)
      }
    }
  }

  // --- Tests for DROTH-1 Automatics for fixing speed limits after geometry update (using VVH change info data)

  test("Should map speed limit of old link to three new links (divided road link, change types 5 and 6)") {

    val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val service = new SpeedLimitService(new DummyEventBus, mockVVHClient, mockRoadLinkService) {
      override def withDynTransaction[T](f: => T): T = f
    }

    val oldLinkId = 4l
    val newLinkId1 = 5l
    val newLinkId2 = 6l
    val newLinkId3 = 7l
    val municipalityCode = 235
    val administrativeClass = Municipality
    val trafficDirection = TrafficDirection.BothDirections
    val featureClass = FeatureClass.AllOthers
    val functionalClass = 1
    val linkType = Freeway
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))

    val oldRoadLink = RoadLink(oldLinkId, List(Point(0.0, 0.0), Point(12.0, 0.0)), 12.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode)))

    val newRoadLinks = Seq(RoadLink(newLinkId1, List(Point(0.0, 0.0), Point(3.0, 0.0)), 3.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))),
      RoadLink(newLinkId2, List(Point(0.0, 0.0), Point(5.0, 0.0)), 5.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))),
      RoadLink(newLinkId3, List(Point(0.0, 0.0), Point(4.0, 0.0)), 4.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))))

    val changeInfo = Seq(ChangeInfo(Some(oldLinkId), Some(newLinkId1), 12345, 5, Some(0), Some(3), Some(0), Some(3), Some(144000000)),
      ChangeInfo(Some(oldLinkId), Some(newLinkId2), 12346, 6, Some(3), Some(8), Some(0), Some(5), Some(144000000)),
      ChangeInfo(Some(oldLinkId), Some(newLinkId3), 12347, 6, Some(8), Some(12), Some(0), Some(4), Some(144000000)))

    OracleDatabase.withDynTransaction {
      sqlu"""insert into lrm_position (id, link_id, mml_id, start_measure, end_measure, side_code) VALUES (1, $oldLinkId, null, 0.000, 12.000, ${SideCode.BothDirections.value})""".execute
      sqlu"""insert into asset (id,asset_type_id,floating) values (1,20,0)""".execute
      sqlu"""insert into asset_link (asset_id,position_id) values (1,1)""".execute
      sqlu"""insert into single_choice_value (asset_id,enumerated_value_id,property_id) values (1,(select id from enumerated_value where value = 70),(select id from property where public_id = 'rajoitus'))""".execute

      when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((List(oldRoadLink), Nil))

      val before = service.get(boundingBox, Set(municipalityCode)).toList
      before.length should be(1)
      before.head.foreach(_.value should be(Some(NumericValue(70))))

      when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((newRoadLinks, changeInfo))

      val after = service.get(boundingBox, Set(municipalityCode)).toList
      after.length should be(3)
      after.head.foreach(_.value should be(Some(NumericValue(70))))

      dynamicSession.rollback()    }
  }

  test("Should map speed limit of three old links to one new link (combined road link, change types 1 and 2)") {

    val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
    val mockVVHClient = MockitoSugar.mock[VVHClient]
    val service = new SpeedLimitService(new DummyEventBus, mockVVHClient, mockRoadLinkService) {
      override def withDynTransaction[T](f: => T): T = f
    }

    val oldLinkId1 = 6l
    val oldLinkId2 = 7l
    val oldLinkId3 = 8l
    val newLinkId = 9l
    val municipalityCode = 235
    val administrativeClass = Municipality
    val trafficDirection = TrafficDirection.BothDirections
    val featureClass = FeatureClass.AllOthers
    val functionalClass = 1
    val linkType = Freeway
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))

    val oldRoadLinks = Seq(RoadLink(oldLinkId1, List(Point(0.0, 0.0), Point(3.0, 0.0)), 3.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))),
      RoadLink(oldLinkId2, List(Point(0.0, 0.0), Point(4.0, 0.0)), 4.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))),
      RoadLink(oldLinkId3, List(Point(0.0, 0.0), Point(2.0, 0.0)), 2.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode))))

    val newRoadLink = RoadLink(newLinkId, List(Point(0.0, 0.0), Point(9.0, 0.0)), 9.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, Map("MUNICIPALITYCODE" -> BigInt(municipalityCode)))

    val changeInfo = Seq(ChangeInfo(Some(oldLinkId1), Some(newLinkId), 12345, 1, Some(0), Some(3), Some(0), Some(3), Some(144000000)),
      ChangeInfo(Some(oldLinkId2), Some(newLinkId), 12345, 2, Some(0), Some(4), Some(3), Some(7), Some(144000000)),
      ChangeInfo(Some(oldLinkId3), Some(newLinkId), 12345, 2, Some(0), Some(2), Some(7), Some(9), Some(144000000)))

    OracleDatabase.withDynTransaction {
      sqlu"""insert into lrm_position (id, link_id, mml_id, start_measure, end_measure, side_code) VALUES (1, $oldLinkId1, null, 0.000, 3.000, ${SideCode.BothDirections.value})""".execute
      sqlu"""insert into asset (id,asset_type_id,floating) values (1,20,0)""".execute
      sqlu"""insert into asset_link (asset_id,position_id) values (1,1)""".execute
      sqlu"""insert into single_choice_value (asset_id,enumerated_value_id,property_id) values (1,(select id from enumerated_value where value = 70),(select id from property where public_id = 'rajoitus'))""".execute
      sqlu"""insert into lrm_position (id, link_id, mml_id, start_measure, end_measure, side_code) VALUES (2, $oldLinkId2, null, 0.000, 4.000, ${SideCode.BothDirections.value})""".execute
      sqlu"""insert into asset (id,asset_type_id,floating) values (2,20,0)""".execute
      sqlu"""insert into asset_link (asset_id,position_id) values (2,2)""".execute
      sqlu"""insert into single_choice_value (asset_id,enumerated_value_id,property_id) values (2,(select id from enumerated_value where value = 70),(select id from property where public_id = 'rajoitus'))""".execute
      sqlu"""insert into lrm_position (id, link_id, mml_id, start_measure, end_measure, side_code) VALUES (3, $oldLinkId3, null, 0.000, 2.000, ${SideCode.BothDirections.value})""".execute
      sqlu"""insert into asset (id,asset_type_id,floating) values (3,20,0)""".execute
      sqlu"""insert into asset_link (asset_id,position_id) values (3,3)""".execute
      sqlu"""insert into single_choice_value (asset_id,enumerated_value_id,property_id) values (3,(select id from enumerated_value where value = 70),(select id from property where public_id = 'rajoitus'))""".execute

      when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((oldRoadLinks, Nil))

      val before = service.get(boundingBox, Set(municipalityCode)).toList
      before.length should be(3)
      before.head.foreach(_.value should be(Some(NumericValue(70))))

      when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((List(newRoadLink), changeInfo))

      val after = service.get(boundingBox, Set(municipalityCode)).toList
      after.length should be(1)
      after.head.foreach(_.value should be(Some(NumericValue(70))))

      dynamicSession.rollback()    }
  }


}