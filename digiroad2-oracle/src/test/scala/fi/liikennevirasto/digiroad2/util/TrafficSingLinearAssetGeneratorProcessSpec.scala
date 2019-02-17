package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.TowardsDigitizing
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh._
import fi.liikennevirasto.digiroad2.dao.linearasset.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.dao.pointasset.PersistedTrafficSign
import fi.liikennevirasto.digiroad2.linearasset.{PersistedLinearAsset, ProhibitionValue, Prohibitions, RoadLink}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.ProhibitionService
import fi.liikennevirasto.digiroad2.service.pointasset.{TrafficSignService, TrafficSignToGenerateLinear}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class TrafficSingLinearAssetGeneratorProcessSpec extends FunSuite with Matchers {
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockVVHClient = MockitoSugar.mock[VVHClient]
  val mockLinearAssetDao = MockitoSugar.mock[OracleLinearAssetDao]
  val mockProhibitionService = MockitoSugar.mock[ProhibitionService]
  val mockTrafficSignService = MockitoSugar.mock[TrafficSignService]

  def service : TrafficSingLinearAssetGeneratorProcess = new TrafficSingLinearAssetGeneratorProcess(mockRoadLinkService) {
    override val oracleLinearAssetDao: OracleLinearAssetDao = mockLinearAssetDao
    override def withDynTransaction[T](f: => T): T = f

    override lazy val prohibitionService: ProhibitionService = mockProhibitionService
  }

  private def runWithRollback(test: => Unit): Unit = TestTransactions.runWithRollback()(test)

  val roadLinkNameB1 = RoadLink(1005, Seq(Point(30.0, 20.0), Point(40.0, 20.0)), GeometryUtils.geometryLength(Seq(Point(30.0, 20.0), Point(40.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI" -> "Name B"))
  val roadLinkNameB2 = RoadLink(1010, Seq(Point(40.0, 20.0), Point(50.0, 20.0)), GeometryUtils.geometryLength(Seq(Point(40.0, 20.0), Point(50.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI" -> "Name B"))

  val vvhRoadLinkNameA = VVHRoadlink(1000, 235, Seq(Point(10.0, 20.0), Point(30.0, 20.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = Map("ROADNAME_FI" -> "Name A"))
  val vvhRoadLinkNameB1 = VVHRoadlink(1005, 235, Seq(Point(30.0, 20.0), Point(40.0, 20.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = Map("ROADNAME_FI" -> "Name B"))
  val vvhRoadLinkNameB2 = VVHRoadlink(1010, 235, Seq(Point(40.0, 20.0), Point(50.0, 20.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = Map("ROADNAME_FI" -> "Name B"))
  val vvhRoadLinkNameB3 = VVHRoadlink(1015, 235, Seq(Point(50.0, 20.0), Point(60.0, 20.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = Map("ROADNAME_FI" -> "Name B"))
  val vvhRoadLinkNameC = VVHRoadlink(1020, 235, Seq(Point(60.0, 20.0), Point(70.0, 20.0)), Municipality, TrafficDirection.BothDirections, FeatureClass.AllOthers, attributes = Map("ROADNAME_FI" -> "Name C"))


  //  test("should just return adjacents with the same road name to create prohibition "){
//    runWithRollback{
//      val service = new ProhibitionService(mockRoadLinkService, new DummyEventBus) {
//        override def withDynTransaction[T](f: => T): T = f
//        override def vvhClient: VVHClient = mockVVHClient
//      }
//
//      val sourceRoadLink =  RoadLink(1000, Seq(Point(0.0, 0.0), Point(0.0, 100.0)), GeometryUtils.geometryLength(Seq(Point(0.0, 0.0), Point(0.0, 100.0))), Municipality, 6, TrafficDirection.TowardsDigitizing, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI"->"RoadName_fi"))
//      val roadLink = RoadLink(1001, Seq(Point(0, 100), Point(0, 250)), GeometryUtils.geometryLength(Seq(Point(0, 0), Point(0, 250))), Municipality, 6, TrafficDirection.TowardsDigitizing, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI"->"RoadName_fi"))
//      val roadLink1 =  RoadLink(1002, Seq(Point(0, 250), Point(0, 500)), GeometryUtils.geometryLength(Seq(Point(0, 0), Point(0, 500))), Municipality, 6, TrafficDirection.TowardsDigitizing, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI"->"RoadName"))
//
//      val properties = Set(
//        SimpleTrafficSignProperty("trafficSigns_type", List(TextPropertyValue( ClosedToAllVehicles.OTHvalue.toString))))
//
//      when(mockRoadLinkService.getRoadLinkEndDirectionPoints(any[RoadLink], any[Option[Int]])).thenReturn(Seq(Point(0, 100)))
//
//      when(mockRoadLinkService.getAdjacent(1000, Seq(Point(0, 100)), false)).thenReturn(Seq(roadLink))
//      when(mockRoadLinkService.getAdjacent(1001,  Seq(Point(0, 250)), false)).thenReturn(Seq(roadLink1))
//      when(mockRoadLinkService.getAdjacent(1002,  Seq(Point(0, 500)), false)).thenReturn(Seq.empty)
//
//      val id = trafficSignService.create(IncomingTrafficSign(0, 50, 1000, properties, 2, None), "test_username", sourceRoadLink)
//      val asset = trafficSignService.getPersistedAssetsByIds(Set(id)).head
//
//      val prohibitionIds = service.createBasedOnTrafficSign(TrafficSignInfo(asset.id, asset.linkId, asset.validityDirection, ClosedToAllVehicles.OTHvalue , asset.mValue, sourceRoadLink, Seq()), false)
//      val prohibitions = service.getPersistedAssetsByIds(Prohibition.typeId, prohibitionIds.toSet)
//      prohibitions.length should be (2)
//
//      val first = prohibitions.find(_.linkId == 1000).get
//      first.startMeasure should be (50)
//      first.endMeasure should be (100)
//
//      val second =  prohibitions.find(_.linkId == 1001).get
//      second.startMeasure should be (0)
//      second.endMeasure should be (250)
//    }
//  }

  def toRoadLink(l: VVHRoadlink) = {
    RoadLink(l.linkId, l.geometry, GeometryUtils.geometryLength(l.geometry),
      l.administrativeClass, 1, l.trafficDirection, UnknownLinkType, None, None, l.attributes + ("MUNICIPALITYCODE" -> BigInt(l.municipalityCode)))
  }

  test("create linear asset according traffic sign without pair on same road name") {
    runWithRollback {
      val roadLinkNameB1 = RoadLink(1005, Seq(Point(30.0, 20.0), Point(40.0, 20.0)), GeometryUtils.geometryLength(Seq(Point(30.0, 20.0), Point(40.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI" -> "Name B"))

      when(mockRoadLinkService.getRoadLinkByLinkIdFromVVH(any[Long], any[Boolean])).thenReturn(Some(roadLinkNameB1))
      when(mockRoadLinkService.fetchVVHRoadlinks(Set("Name B"), "ROADNAME_FI")).thenReturn(Seq(vvhRoadLinkNameB1, vvhRoadLinkNameB2, vvhRoadLinkNameB3))

      val properties = Seq(TrafficSignProperty(0, "trafficSigns_type", "", false, Seq(TextPropertyValue(NoPowerDrivenVehicles.OTHvalue.toString))))

      val trafficSign = PersistedTrafficSign(1, 1005, 32, 20, 2, false, 0, 235, properties, None, None, None, None, SideCode.TowardsDigitizing.value, None, NormalLinkInterface)
      //      val pairedTrafficSign = PersistedTrafficSign(1, 1015, 48, 20, 2, false, 0, 235, properties, None, None, None, None, SideCode.AgainstDigitizing.value, None, NormalLinkInterface)

//      when(mockTrafficSignService.getTrafficSign(any[Seq[Long]])).thenReturn(Seq(trafficSign))

      service.createLinearXXXX(trafficSign, Seq(vvhRoadLinkNameB1))

    }
  }

  test("create linear asset according traffic sign with pair on same road name"){
    runWithRollback{
      val roadLinkNameB1 = RoadLink(1005, Seq(Point(30.0, 20.0), Point(40.0, 20.0)), GeometryUtils.geometryLength(Seq(Point(30.0, 20.0), Point(40.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "ROADNAME_FI" -> "Name B"))

      when(mockRoadLinkService.fetchVVHRoadlinks(Set("Name B"), "ROADNAME_FI" )).thenReturn(Seq(vvhRoadLinkNameB1, vvhRoadLinkNameB2, vvhRoadLinkNameB3))
      when(mockRoadLinkService.enrichRoadLinksFromVVH(any[Seq[VVHRoadlink]], any[Seq[ChangeInfo]])).thenReturn(Seq())

      val properties = Seq(TrafficSignProperty(0, "trafficSigns_type", "", false, Seq(TextPropertyValue(NoPowerDrivenVehicles.OTHvalue.toString))))

      val trafficSign = PersistedTrafficSign(1, 1005, 32, 20, 2, false, 0, 235, properties, None, None, None, None, SideCode.TowardsDigitizing.value, None, NormalLinkInterface)
      val pairedTrafficSign = PersistedTrafficSign(2, 1015, 58, 20, 8, false, 0, 235, properties, None, None, None, None, SideCode.AgainstDigitizing.value, None, NormalLinkInterface)

//      when(mockTrafficSignService.getTrafficSign(any[Seq[Long]])).thenReturn(Seq(trafficSign, pairedTrafficSign))

      val result = service.createLinearXXXX(trafficSign, Seq(vvhRoadLinkNameB1))
      println(result)
    }
  }

  test("create linear segments"){
      val valueA = ProhibitionValue(NoLeftTurn.OTHvalue, Set.empty, Set.empty, null)
      val valueB = ProhibitionValue(NoPowerDrivenVehicles.OTHvalue, Set.empty, Set.empty, null)
      val valueC = ProhibitionValue(NoPedestriansCyclesMopeds.OTHvalue, Set.empty, Set.empty, null)
      val valueD = ProhibitionValue(NoRidersOnHorseback.OTHvalue, Set.empty, Set.empty, null)
      val valueE = ProhibitionValue(NoMopeds.OTHvalue, Set.empty, Set.empty, null)

      val segment11 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueB)), SideCode.TowardsDigitizing, 2, 4, Seq(1))
      val segment12 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueB)), SideCode.AgainstDigitizing, 2, 4, Seq(2))
      val segment21 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueC)), SideCode.TowardsDigitizing, 6, 8, Seq(3))
      val segment31 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueD)), SideCode.TowardsDigitizing, 4, 5, Seq(4))
      val segment32 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueD)), SideCode.AgainstDigitizing, 4, 5, Seq(5))
      val segment42 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueE)), SideCode.AgainstDigitizing, 8, 10, Seq(6))

      val oldAsset = PersistedLinearAsset(1, 1005, SideCode.BothDirections.value, Some(Prohibitions(Seq(valueA))), 2, 10, None, None, None, None, false, Prohibition.typeId, 0, None, LinkGeomSource.NormalLinkInterface, None, None, None)
      when(mockProhibitionService.getPersistedAssetsByLinkIds(any[Int], any[Seq[Long]])).thenReturn(Seq(oldAsset))

      val segments = Seq(segment11, segment12, segment21, segment31, segment32, segment42 )
      val result = service.splitSegments(Seq(vvhRoadLinkNameB1, vvhRoadLinkNameB2, vvhRoadLinkNameB3), segments,  Seq(vvhRoadLinkNameB1, vvhRoadLinkNameB3))
      result.size should be (10)
      val (seg11, seg12) = result.filter(res => res.startMeasure == 2 && res.endMeasure == 4).partition(_.sideCode == TowardsDigitizing)
      seg11.exists( seg => Seq(valueA,valueB).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg12.exists( seg => Seq(valueA,valueB).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg11.exists( seg => seg.signId.contains(1)) should be (true)
      val (seg21, seg22) = result.filter(res => res.startMeasure == 4 && res.endMeasure == 5).partition(_.sideCode == TowardsDigitizing)
      seg21.exists( seg => Seq(valueA,valueD).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg22.exists( seg => Seq(valueA,valueD).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      val (seg31, seg32) = result.filter(res => res.startMeasure == 5 && res.endMeasure == 6).partition(_.sideCode == TowardsDigitizing)
      seg31.exists( seg => Seq(valueA).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg32.exists( seg => Seq(valueA).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      val (seg41, seg42) = result.filter(res => res.startMeasure == 6 && res.endMeasure == 8).partition(_.sideCode == TowardsDigitizing)
      seg41.exists( seg => Seq(valueA, valueC).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg42.exists( seg => Seq(valueA).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      val (seg51, seg52) = result.filter(res => res.startMeasure == 8 && res.endMeasure == 10).partition(_.sideCode == TowardsDigitizing)
      seg51.exists( seg => Seq(valueA).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
      seg52.exists( seg => Seq(valueA,valueE).equals(seg.value.asInstanceOf[Prohibitions].prohibitions)) should be (true)
  }

  test("test combine assets"){
    val valueA = ProhibitionValue(NoLeftTurn.OTHvalue, Set.empty, Set.empty, null)
    val valueB = ProhibitionValue(NoPowerDrivenVehicles.OTHvalue, Set.empty, Set.empty, null)
    val valueC = ProhibitionValue(NoPedestriansCyclesMopeds.OTHvalue, Set.empty, Set.empty, null)
    val valueD = ProhibitionValue(NoRidersOnHorseback.OTHvalue, Set.empty, Set.empty, null)
    val valueE = ProhibitionValue(NoMopeds.OTHvalue, Set.empty, Set.empty, null)

    val segment11 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueB)), SideCode.TowardsDigitizing, 2, 4, Seq(1))
    val segment12 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueB)), SideCode.AgainstDigitizing, 2, 4, Seq(2))
    val segment21 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueD)), SideCode.TowardsDigitizing, 4, 5, Seq(4))
    val segment22 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueD)), SideCode.AgainstDigitizing, 4, 5, Seq(5))
    val segment31 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA)), SideCode.TowardsDigitizing, 5, 6, Seq())
    val segment41 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueC)), SideCode.TowardsDigitizing, 6, 8, Seq(1))
    val segment42 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA)), SideCode.AgainstDigitizing, 6, 8, Seq(2))
    val segment52 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA, valueE)), SideCode.AgainstDigitizing, 8, 10, Seq(4))

    val segments = Seq(segment11, segment12, segment21, segment22, segment31, segment41, segment42, segment52)

    val (assetForBothSide, oneSide) = service.fuseSegments(segments)
    assetForBothSide.size should be (4)
    oneSide.size should be (2)
    assetForBothSide.exists(seg => seg.startMeasure == 2 && seg.endMeasure == 4) should be (true)
    assetForBothSide.exists(seg => seg.startMeasure == 4 && seg.endMeasure == 5) should be (true)
    assetForBothSide.exists(seg => seg.startMeasure == 6 && seg.endMeasure == 8) should be (true)
    oneSide.count(seg => seg.startMeasure == 5 && seg.endMeasure == 6) should be (1)
    oneSide.count(seg => seg.startMeasure == 8 && seg.endMeasure == 10) should be (1)
  }

  test("test try convert oneSide segments into bothSide"){
    val adjRoadLinks = Seq(RoadLink(2000, Seq(Point(50.0, 20.0), Point(60.0, 0.0)), GeometryUtils.geometryLength(Seq(Point(40.0, 20.0), Point(50.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None),
    RoadLink(3000, Seq(Point(50.0, 20.0), Point(60.0, 20.0)), GeometryUtils.geometryLength(Seq(Point(40.0, 20.0), Point(50.0, 20.0))), Municipality, 6, TrafficDirection.BothDirections, Motorway, None, None))

    val valueA = ProhibitionValue(NoLeftTurn.OTHvalue, Set.empty, Set.empty, null)
    val valueE = ProhibitionValue(NoMopeds.OTHvalue, Set.empty, Set.empty, null)

    val segment11 = TrafficSignToGenerateLinear(vvhRoadLinkNameB1, Prohibitions(Seq(valueA)), SideCode.TowardsDigitizing, 0, 10, Seq())
    val segment22 = TrafficSignToGenerateLinear(vvhRoadLinkNameB2, Prohibitions(Seq(valueA, valueE)), SideCode.AgainstDigitizing, 0, 10, Seq(4))

    val segments = Set(segment11, segment22)
    when(mockRoadLinkService.enrichRoadLinksFromVVH(any[Seq[VVHRoadlink]], any[Seq[ChangeInfo]])).thenReturn(Seq(roadLinkNameB1))
    when( mockRoadLinkService.getAdjacent(vvhRoadLinkNameB1.linkId)).thenReturn(Seq(roadLinkNameB2))
    when( mockRoadLinkService.getAdjacent(vvhRoadLinkNameB2.linkId)).thenReturn(adjRoadLinks)

    val result = service.convertOneSideCode(segments, Seq(vvhRoadLinkNameB1, vvhRoadLinkNameB3))
    result.size should be (4)

  }

}
