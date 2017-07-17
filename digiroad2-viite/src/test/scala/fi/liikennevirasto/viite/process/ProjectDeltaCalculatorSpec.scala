package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Sequences
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.Discontinuous
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.{LinkStatus, _}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

/**
  * Created by pedrosag on 14-07-2017.
  */
class ProjectDeltaCalculatorSpec  extends FunSuite with Matchers{

  private def toProjectLink(project: RoadAddressProject)(roadAddress: RoadAddress): ProjectLink = {
    ProjectLink(roadAddress.id, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
      roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
      roadAddress.endDate, modifiedBy=Option(project.createdBy), 0L, roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
      roadAddress.sideCode, roadAddress.calibrationPoints, floating=false, roadAddress.geom, project.id, LinkStatus.NotHandled, RoadType.PublicRoad)
  }



  test("validate correct mValuesCreation") {
    val idRoad0 = 0L
    val idRoad1 =1L
    val idRoad2 = 2L
    val projectId = 1
    val baseLength = 100L
    val rap = RoadAddressProject(projectId, ProjectState.apply(1), "TestProject", "TestUser", DateTime.parse("2700-01-01"), "TestUser", DateTime.parse("1972-03-03"), DateTime.parse("2700-01-01"), "Some additional info", List.empty[ReservedRoadPart], None)
    val projectLink0 = toProjectLink(rap)(RoadAddress(idRoad0, 5, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
      Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface))
    val projectLink1 = toProjectLink(rap)(RoadAddress(idRoad1, 5, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
      Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface))
    val projectLink2 = toProjectLink(rap)(RoadAddress(idRoad2, 5, 1, RoadType.Unknown, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), Some(DateTime.parse("1902-01-01")), Option("tester"), 0, 12345L, 0.0, 9.8, SideCode.TowardsDigitizing, 0, (None, None), false,
      Seq(Point(0.0, 0.0), Point(0.0, 9.8)), LinkGeomSource.NormalLinkInterface))

    val projectLinkSeq = Seq(projectLink0, projectLink1, projectLink2)
    var linkLengths: Map[Long, Seq[RoadPartLengths]] = Map.empty
    projectLinkSeq.foreach(pl => {
      linkLengths = linkLengths + (pl.linkId -> Seq(RoadPartLengths(pl.roadNumber, pl.roadPartNumber, baseLength)))
    })

    val output = ProjectDeltaCalculator.determineMValues(projectLinkSeq, linkLengths)
    output.length should be(3)
	
    output(0).id should be(idRoad0)
    output(0).startMValue should be(0.0)
    output(0).endMValue should be(baseLength)

    output(1).id should be(idRoad1)
    output(1).startMValue should be(baseLength)
    output(1).endMValue should be(baseLength*2)

    output(2).id should be(idRoad2)
    output(2).startMValue should be(baseLength*2)
    output(2).endMValue should be(baseLength*3)
  }

}
