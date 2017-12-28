package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.dao.Discontinuity._
import fi.liikennevirasto.viite.dao.LinkStatus._
import fi.liikennevirasto.viite.dao._

object ProjectValidator {

  sealed trait ValidationError {
    def value: Int
    def message: String
    def notification: Boolean
  }

  object ValidationError {
    val values = Set(MinorDiscontinuityFound, MajorDiscontinuityFound, InsufficientTrackCoverage, DiscontinuousAddressScheme,
      SharedLinkIdsExist, NoContinuityCodesAtEnd, UnsuccessfulRecalculation, MissingEndOfRoad)
    //Viite-942
    case object MissingEndOfRoad extends ValidationError {def value = 0
      def message = MissingEndOfRoadMessage
      def notification = false}
    //Viite-453
    //There must be a minor discontinuity if the jump is longer than 0.1 m (10 cm) between road links
    case object MinorDiscontinuityFound extends ValidationError {
      def value = 1
      def message = MinorDiscontinuityFoundMessage
      def notification = true}
    //Viite-453
    //There must be a major discontinuity if the jump is longer than 50 meters
    case object MajorDiscontinuityFound extends ValidationError {
      def value = 2
      def message = MajorDiscontinuityFoundMessage
      def notification = false}
    //Viite-453
    //For every track 1 there must exist track 2 that covers the same address span and vice versa
    case object InsufficientTrackCoverage extends ValidationError {
      def value = 3
      def message = InsufficientTrackCoverageMessage
      def notification = false}
    //Viite-453
    //There must be a continuous road addressing scheme so that all values from 0 to the highest number are covered
    case object DiscontinuousAddressScheme extends ValidationError {
      def value = 4
      def message = DiscontinuousAddressSchemeMessage
      def notification = false}
    //Viite-453
    //There are no link ids shared between the project and the current road address + lrm_position tables at the project date (start_date, end_date)
    case object SharedLinkIdsExist extends ValidationError {
      def value = 5
      def message = SharedLinkIdsExistMessage
      def notification = false}
    //Viite-453
    //Continuity codes are given for end of road
    case object NoContinuityCodesAtEnd extends ValidationError {
      def value = 6
      def message = NoContinuityCodesAtEndMessage
      def notification = false}
    //Viite-453
    //Recalculation of M values and delta calculation are both unsuccessful for every road part in project
    case object UnsuccessfulRecalculation extends ValidationError {
      def value = 7
      def message = UnsuccessfulRecalculationMessage
      def notification = false}

    case object HasNotHandledLinks extends ValidationError{
      def value = 8
      def message = ""
      def notification = false
    }

    case object ConnectedDiscontinuousLink extends ValidationError {
      def value = 9
      def message = ConnectedDiscontinuousMessage
      def notification = false}

    case object IncompatibleDiscontinuityCodes extends ValidationError {
      def value = 10
      def message = DifferingDiscontinuityCodesForTracks
      def notification = false}

    case object EndOfRoadNotOnLastPart extends ValidationError {
      def value = 11
      def message = EndOfRoadNotOnLastPartMessage
      def notification = false}

    case object ElyCodeChangeDetected extends ValidationError {
      def value = 12
      def message = ElyCodeChangeNotPresent
      def notification = false}

    def apply(intValue: Int): ValidationError = {
      values.find(_.value == intValue).get
    }
  }

  case class ValidationErrorDetails(projectId: Long, validationError: ValidationError,
                                    affectedLinkIds: Seq[Long], coordinates: Seq[ProjectCoordinates],
                                    optionalInformation: Option[String])

  def validateProject(project: RoadAddressProject, projectLinks: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {

    def checkProjectContinuity: Seq[ValidationErrorDetails] =
      projectLinks.filter(_.status != Terminated).groupBy(pl => (pl.roadNumber, pl.roadPartNumber)).flatMap {
        case ((road, _), seq) =>
          if (road < RampsMinBound || road > RampsMaxBound) {
            checkOrdinaryRoadContinuityCodes(project, seq)
          } else {
            Seq()
          }
        case _ => Seq()
      }.toSeq

    def checkProjectCoverage = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkProjectContinuousSchema = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkProjectSharedLinks = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForContinuityCodes = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForUnsuccessfulRecalculation = {
      Seq.empty[ValidationErrorDetails]
    }

    def checkForNotHandledLinks = {
      val notHandled = projectLinks.filter(_.status == LinkStatus.NotHandled)
      notHandled.groupBy(link => (link.roadNumber, link.roadPartNumber)).foldLeft(Seq.empty[ValidationErrorDetails])((errorDetails, road) =>
        errorDetails :+ ValidationErrorDetails(project.id, ValidationError.HasNotHandledLinks,
          Seq(road._2.size), road._2.map(l => ProjectCoordinates(l.geometry.head.x, l.geometry.head.y, 12)),
          Some(HasNotHandledLinksMessage.format(road._2.size, road._1._1, road._1._2)))
      )
    }

    checkProjectContinuity ++ checkProjectCoverage ++ checkProjectContinuousSchema ++ checkProjectSharedLinks ++
      checkForContinuityCodes ++ checkForUnsuccessfulRecalculation ++ checkForNotHandledLinks
  }

  def checkRemovedEndOfRoadParts(project: RoadAddressProject): Seq[ValidationErrorDetails] = {
    // Pick only parts that have no length anymore and had end of road given before
    project.reservedParts.filter(rrp => rrp.addressLength.nonEmpty && rrp.newLength.getOrElse(0L) == 0L &&
      rrp.discontinuity.contains(EndOfRoad))

      // There is no part after this part in project
      .filterNot(rrp => project.reservedParts.exists(np => np.roadNumber == rrp.roadNumber &&
      np.roadPartNumber > rrp.roadPartNumber &&  np.newLength.getOrElse(0L) > 0L))

      // There is no part that is not in this project that comes after this part (rrp)
      .filterNot(rrp => RoadAddressDAO.getValidRoadParts(rrp.roadNumber).exists(l => l > rrp.roadPartNumber &&
      !project.reservedParts.exists(p => p.roadNumber == rrp.roadNumber && p.roadPartNumber == l)))

      // There is no EndOfRoad in this project for this road
      .filterNot(rrp => project.reservedParts.exists(np => np.roadNumber == rrp.roadNumber && np.roadPartNumber < rrp.roadPartNumber &&
      np.newLength.getOrElse(0L) > 0L && np.discontinuity.contains(EndOfRoad)))
      .map { rrp =>
        ValidationErrorDetails(project.id, ValidationError.MissingEndOfRoad, Seq(),
          Seq(), Some(s"TIE ${rrp.roadNumber} OSA ${rrp.roadPartNumber}"))
      }
  }

  /**
    * Check for non-ramp and roundabout roads:
    * 1) If inside a part there is a gap between links > .1 meters, discontinuity 4 (minor) is required
    * 2) If inside a part there is no gap, discontinuity 5 (cont) is required
    * 3) End of road part, discontinuity 2 or 3 (major, ely change) is required if there is a gap
    * 4) If a part that contained end of road discontinuity is terminated / renumbered / transferred,
    *    there must be a new end of road link for that road at the last part
    * 5) If the next road part has differing ely code then there must be a discontinuity code 3 at the end
    * @param project
    * @param seq
    * @return
    */
  def checkOrdinaryRoadContinuityCodes(project: RoadAddressProject, seq: Seq[ProjectLink]): Seq[ValidationErrorDetails] = {
    def trackMatch(track1: Track, track2: Track) = {
      track1 == track2 || track1 == Track.Combined || track2 == Track.Combined
    }
    def connected(pl1: BaseRoadAddress, pl2: BaseRoadAddress) = {
      GeometryUtils.areAdjacent(pl1.geometry, pl2.geometry, fi.liikennevirasto.viite.MaxDistanceForConnectedLinks)
    }
    def error(validationError: ValidationError)(pl: Seq[ProjectLink]) = {
      val (linkIds, points) = pl.map(pl => (pl.linkId, GeometryUtils.midPointGeometry(pl.geometry))).unzip
      if (linkIds.nonEmpty)
        Some(ValidationErrorDetails(project.id, validationError, linkIds,
          points.map(p => ProjectCoordinates(p.x, p.y, 12)), None))
      else
        None
    }
    def checkConnectedAreContinuous = {
      error(ValidationError.ConnectedDiscontinuousLink)(seq.filterNot(pl =>
        // Check that pl is continuous or after it there is no connected project link
        pl.discontinuity == Continuous ||
          !seq.exists(pl2 => pl2.startAddrMValue == pl.endAddrMValue && trackMatch(pl2.track, pl.track) && connected(pl2, pl))
      ))
    }
    def checkDiscontinuousAreNotConnected = {
      error(ValidationError.MinorDiscontinuityFound)(seq.filterNot{pl =>
        // Check that pl has discontinuity or after it the project links are connected (except last, where forall is true for empty list)
        pl.discontinuity == MinorDiscontinuity ||
          seq.forall(pl2 => pl2.startAddrMValue == pl.endAddrMValue && trackMatch(pl2.track, pl.track) && !connected(pl2, pl))
      })
    }
    def checkRoadPartEnd(lastProjectLinks: Seq[ProjectLink]): Option[ValidationErrorDetails] = {
      if (lastProjectLinks.exists(_.discontinuity != lastProjectLinks.head.discontinuity))
        error(ValidationError.IncompatibleDiscontinuityCodes)(lastProjectLinks)
      else {
        val (road, part) = (lastProjectLinks.head.roadNumber, lastProjectLinks.head.roadPartNumber)
        val discontinuity = lastProjectLinks.head.discontinuity
        val ely = lastProjectLinks.head.ely
        val projectNextRoadParts = project.reservedParts.filter(rp =>
          rp.roadNumber == road && rp.roadPartNumber > part)

        val nextProjectPart = projectNextRoadParts.filter(_.newLength.getOrElse(0L) > 0L)
          .map(_.roadPartNumber).sorted.headOption
        val nextAddressPart = RoadAddressDAO.getValidRoadParts(road.toInt, project.startDate)
          .filterNot(p => projectNextRoadParts.exists(_.roadPartNumber == p)).sorted.headOption
        if (nextProjectPart.isEmpty && nextAddressPart.isEmpty && lastProjectLinks.head.discontinuity != EndOfRoad)
          return error(ValidationError.MissingEndOfRoad)(lastProjectLinks)
        else {
          val nextLinks =
            if (nextProjectPart.nonEmpty && (nextAddressPart.isEmpty || nextProjectPart.get < nextAddressPart.get))
              ProjectDAO.fetchByProjectRoadPart(road, nextProjectPart.get, project.id).filter(_.startAddrMValue == 0L)
            else
              RoadAddressDAO.fetchByRoadPart(road, part, includeFloating = true, includeExpired = false, includeHistory = false)
                .filter(_.startAddrMValue == 0L)
          val isConnected = lastProjectLinks.forall(lpl => nextLinks.exists(nl => trackMatch(nl.track, lpl.track) &&
            connected(lpl, nl)))
          val isDisConnected = !lastProjectLinks.exists(lpl => nextLinks.exists(nl => trackMatch(nl.track, lpl.track) &&
            connected(lpl, nl)))
          if (isDisConnected) {
            discontinuity match {
              case Continuous | MinorDiscontinuity =>
                return error(ValidationError.MajorDiscontinuityFound)(lastProjectLinks)
              case EndOfRoad =>
                return error(ValidationError.MissingEndOfRoad)(lastProjectLinks)
              case _ => // no error, continue
            }
          }
          if (isConnected) {
            discontinuity match {
              case MinorDiscontinuity | Discontinuous =>
                return error(ValidationError.ConnectedDiscontinuousLink)(lastProjectLinks)
            }
          }
          if (nextLinks.exists(_.ely != ely) && discontinuity != ChangingELYCode)
            return error(ValidationError.ElyCodeChangeDetected)(lastProjectLinks)
        }
        None
      }
    }
    // Checks inside road part (not including last links' checks)
    checkConnectedAreContinuous.toSeq ++ checkDiscontinuousAreNotConnected.toSeq ++
      checkRoadPartEnd(seq.filter(_.endAddrMValue == seq.maxBy(_.endAddrMValue).endAddrMValue)).toSeq
  }
}

class ProjectValidationException(s: String) extends RuntimeException {
  override def getMessage: String = s
}
