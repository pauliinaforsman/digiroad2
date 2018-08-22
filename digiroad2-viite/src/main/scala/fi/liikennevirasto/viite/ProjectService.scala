package fi.liikennevirasto.viite

import java.util.Date

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{Unknown => _, apply => _}
import fi.liikennevirasto.digiroad2.asset.SideCode.AgainstDigitizing
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, TrafficDirection, _}
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHHistoryRoadLink, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, RoadPartReservedException, Track}
import fi.liikennevirasto.viite.ProjectValidator.ValidationErrorDetails
import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao.LinkStatus.{Terminated, Unknown => _, apply => _, _}
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.TerminationCode.{NoTermination, Subsequent, Termination}
import fi.liikennevirasto.viite.dao.{LinkStatus, ProjectDAO, RoadAddressDAO, _}
import fi.liikennevirasto.viite.model.{Anomaly, ProjectAddressLink, RoadAddressLink}
import fi.liikennevirasto.viite.process._
import fi.liikennevirasto.viite.util.{GuestimateGeometryForMissingLinks, ProjectLinkSplitter, SplitOptions, SplitResult}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

case class PreFillInfo(RoadNumber: BigInt, RoadPart: BigInt)

case class LinkToRevert(id: Long, linkId: Long, status: Long, geometry: Seq[Point])

class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus, useFrozenVVHLinks: Boolean = false) {

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private val guessGeom = new GuestimateGeometryForMissingLinks
  private val logger = LoggerFactory.getLogger(getClass)
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)

  private def withTiming[T](f: => T, s: String): T = {
    val startTime = System.currentTimeMillis()
    val t = f
    logger.info(s.format((System.currentTimeMillis() - startTime) * 0.001))
    t
  }

  /**
    *
    * @param roadNumber    Road's number (long)
    * @param roadStartPart Starting part (long)
    * @param roadEndPart   Ending part (long)
    * @return Optional error message, None if no error
    */
  def checkRoadPartsExist(roadNumber: Long, roadStartPart: Long, roadEndPart: Long): Option[String] = {
    withDynTransaction {
      if (!RoadAddressDAO.roadPartExists(roadNumber, roadStartPart)) {
        if (!RoadAddressDAO.roadNumberExists(roadNumber)) {
          Some(ErrorRoadNumberDoesNotExist)
        }
        else //roadnumber exists, but starting roadpart not
          Some(ErrorStartingRoadPartNotFound)
      } else if (!RoadAddressDAO.roadPartExists(roadNumber, roadEndPart)) { // ending part check
        Some(ErrorEndingRoadPartNotFound)
      } else
        None
    }
  }

  def calculateProjectCoordinates(projectId: Long, resolution: Int): ProjectCoordinates = {
    withDynTransaction{
      val links = ProjectDAO.getProjectLinks(projectId)
      if (links.nonEmpty) {
        val corners = GeometryUtils.boundingRectangleCorners(links.flatten(_.geometry))
        val centerX = (corners._1.x + corners._2.x) / 2
        val centerY = (corners._1.y + corners._2.y) / 2
        val (xLength,yLength) = (Math.abs(corners._2.x - corners._1.x), Math.abs(corners._2.y - corners._1.y))
        val zoom = Resolutions.map(r => {
          (xLength / r, yLength / r) match {
            case (x, y) if x < DefaultScreenWidth && y < DefaultScreenHeight  => Resolutions.indexOf(r)
            case _ => 0
          }
        })
        ProjectCoordinates(centerX, centerY, zoom.max)
      } else {
        ProjectCoordinates(0,0,0)
      }
    }
  }

  def saveProjectCoordinates(projectId: Long, coordinates: ProjectCoordinates): Unit = {
    withDynSession {
      ProjectDAO.updateProjectCoordinates(projectId, coordinates)
    }
  }

  private def createProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = roadAddressProject.copy(id = id)
    ProjectDAO.createRoadAddressProject(project)
    val error = addLinksToProject(project)
    if (error.nonEmpty)
      throw new RoadPartReservedException(error.get)
    ProjectDAO.getRoadAddressProjectById(id).get
  }

  private def projectFound(roadAddressProject: RoadAddressProject): Option[RoadAddressProject] = {
    val newRoadAddressProject = 0
    if (roadAddressProject.id == newRoadAddressProject) return None
    withDynTransaction {
      return ProjectDAO.getRoadAddressProjectById(roadAddressProject.id)
    }
  }

  def fetchPreFillFromVVH(linkId: Long): Either[String, PreFillInfo] = {
    parsePreFillData(roadLinkService.fetchVVHRoadlinks(Set(linkId), useFrozenVVHLinks))
  }

  def parsePreFillData(vvhRoadLinks: Seq[VVHRoadlink]): Either[String, PreFillInfo] = {
    if (vvhRoadLinks.isEmpty) {
      Left("Link could not be found in VVH")
    }
    else {
      val vvhLink = vvhRoadLinks.head
      (vvhLink.attributes.get("ROADNUMBER"), vvhLink.attributes.get("ROADPARTNUMBER")) match {
        case (Some(roadNumber: BigInt), Some(roadPartNumber: BigInt)) => {
          Right(PreFillInfo(roadNumber, roadPartNumber))
        }
        case _ => Left("Link does not contain valid prefill info")
      }
    }
  }

  def checkRoadPartsReservable(roadNumber: Long, startPart: Long, endPart: Long): Either[String, Seq[ReservedRoadPart]] = {
    withDynTransaction {
      (startPart to endPart).foreach(part =>
        ProjectDAO.roadPartReservedByProject(roadNumber, part) match {
          case Some(name) => return Left(s"Tie $roadNumber osa $part ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna toisessa projektissa $name.")
          case _ =>
        })
      Right((startPart to endPart).flatMap(part => getAddressPartInfo(roadNumber, part))
      )
    }
  }


  /**
    *
    * @param projectId project's id
    * @return if state of the project is incomplete
    */

  def isWritableState(projectId: Long): Boolean = {
    withDynTransaction {
      projectWritableCheck(projectId) match {
        case Some(errorMessage) => false
        case None => true
      }
    }
  }

  private def projectWritableCheck(projectId: Long): Option[String] = {
    ProjectDAO.getProjectStatus(projectId) match {
      case Some(projectState) =>
        if (projectState == ProjectState.Incomplete)
          return None
        Some("Projektin tila ei ole keskeneräinen") //project state is not incomplete
      case None => Some("Projektia ei löytynyt") //project could not be found
    }
  }


  def validateProjectDate(reservedParts: Seq[ReservedRoadPart], date: DateTime): Option[String] = {
    withDynSession {
      reservedParts.map(rp => (rp.roadNumber, rp.roadPartNumber) -> RoadAddressDAO.getRoadPartInfo(rp.roadNumber, rp.roadPartNumber)).toMap.
        filterNot(_._2.isEmpty).foreach {
        case ((roadNumber, roadPartNumber), value) =>
          val (startDate, endDate) = value.map(v => (v._6, v._7)).get
          if(startDate.nonEmpty && startDate.get.isEqual(date) && endDate.isEmpty)
            return Option(s"TIE ${roadNumber} OSA ${roadPartNumber} ei ole vapaana projektin alkupäivämääränä. " +
              s"Tieosoitteen alkupäivämäärä on sama kuin projektin alkupäivämäärä.")
          if (startDate.nonEmpty && startDate.get.isAfter(date))
            return Option(s"Tieosalla TIE ${roadNumber} OSA ${roadPartNumber} alkupäivämäärä " +
              s"${startDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
              s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
          if (endDate.nonEmpty && endDate.get.isAfter(date))
            return Option(s"Tieosalla TIE ${roadNumber} OSA ${roadPartNumber} loppupäivämäärä " +
              s"${endDate.get.toString("dd.MM.yyyy")} on myöhempi kuin tieosoiteprojektin alkupäivämäärä " +
              s"${date.toString("dd.MM.yyyy")}, tarkista tiedot.")
      }
      None
    }
  }

  private def getAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ReservedRoadPart] = {
    ProjectDAO.fetchReservedRoadPart(roadNumber, roadPart).orElse(generateAddressPartInfo(roadNumber, roadPart))
  }

  private def generateAddressPartInfo(roadNumber: Long, roadPart: Long): Option[ReservedRoadPart] = {
    RoadAddressDAO.getRoadPartInfo(roadNumber, roadPart).map {
      case (_, linkId, addrLength, discontinuity, ely, _, _) =>
        ReservedRoadPart(0L, roadNumber, roadPart, Some(addrLength), Some(Discontinuity.apply(discontinuity.toInt)), Some(ely),
          newLength = None, newDiscontinuity = None, newEly = None, Some(linkId))
    }
  }

  def createProjectLinks(linkIds: Seq[Long], projectId: Long, roadNumber: Long, roadPartNumber: Long, track: Track,
                         discontinuity: Discontinuity, roadType: RoadType, roadLinkSource: LinkGeomSource,
                         roadEly: Long, user: String): Map[String, Any] = {
    def sortRamps(seq: Seq[ProjectLink]): Seq[ProjectLink] = {
      if (seq.headOption.exists(isRamp))
        seq.find(l => linkIds.headOption.contains(l.linkId)).toSeq ++ seq.filter(_.linkId != linkIds.headOption.getOrElse(0L))
      else
        seq
    }

    validateLinkTrack(track.value) match {
      case true => {
        val linkId = linkIds.head
        val roadLinks = (if (roadLinkSource != LinkGeomSource.SuravageLinkInterface) {
          roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(linkIds.toSet, true, useFrozenVVHLinks)
        } else {
          roadLinkService.fetchSuravageLinksByLinkIdsFromVVH(linkIds.toSet)
        }).map(l => l.linkId -> l).toMap
        if (roadLinks.keySet != linkIds.toSet)
          return Map("success" -> false,
            "errorMessage" -> (linkIds.toSet - roadLinks.keySet).mkString(ErrorRoadLinkNotFound + " puuttuvat id:t ", ", ", ""))
        val project = withDynSession {
          ProjectDAO.getRoadAddressProjectById(projectId).getOrElse(throw new RuntimeException(s"Missing project $projectId"))
        }
        val projectLinks: Seq[ProjectLink] = linkIds.map { id =>
          newProjectLink(roadLinks(id), project, roadNumber, roadPartNumber, track, discontinuity,
            roadType, roadEly)
        }
        setProjectEly(projectId, roadEly) match {
          case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
          case None => {
            addNewLinksToProject(sortRamps(projectLinks), projectId, user, linkId) match {
              case Some(errorMessage) => Map("success" -> false, "errorMessage" -> errorMessage)
              case None => Map("success" -> true, "publishable" -> isProjectPublishable(projectId))
            }
          }
        }
      }
      case _ => {
        Map("success" -> false, "errorMessage" -> "Invalid track code")
      }
    }
  }

  /**
    * Used when adding road address that do not have a previous address
    */
  def addNewLinksToProject(newLinks: Seq[ProjectLink], projectId: Long, user: String, firstLinkId: Long): Option[String] = {
    val newRoadNumber = newLinks.head.roadNumber
    val newRoadPartNumber = newLinks.head.roadPartNumber
    try {
      withDynTransaction {
        val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber)

        val ely = if (newLinks.nonEmpty)newLinks.head.ely else -1
        if (!project.isReserved(newRoadNumber, newRoadPartNumber))
          ProjectDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
        if (GeometryUtils.isNonLinear(newLinks))
          throw new ProjectValidationException(ErrorGeometryContainsBranches)
        // Determine address value scheme (ramp, roundabout, all others)
        val createLinks =
          if (newLinks.headOption.exists(isRamp)) {
            logger.info("Added links recognized to be in ramp category")
            if (TrackSectionOrder.isRoundabout(newLinks)) {
              logger.info("Added links recognized to be a roundabout - using roundabout addressing scheme")
              val ordered = newLinks.partition(_.linkId == firstLinkId)
              val created = TrackSectionOrder.mValueRoundabout(ordered._1 ++ ordered._2)
              val endingM = created.map(_.endAddrMValue).max
              created.map(pl =>
                if (pl.endAddrMValue == endingM && endingM > 0)
                  pl.copy(discontinuity = Discontinuity.EndOfRoad)
                else
                  pl.copy(discontinuity = Discontinuity.Continuous))
            } else {
              val existingLinks = ProjectDAO.fetchByProjectRoadPart(newRoadNumber, newRoadPartNumber, projectId)
              fillRampGrowthDirection(newLinks.map(_.linkId).toSet, newRoadNumber, newRoadPartNumber, newLinks,
                firstLinkId, existingLinks)
            }
          } else
            newLinks
        ProjectDAO.create(createLinks.map(_.copy(modifiedBy = Some(user))))
        recalculateProjectLinks(projectId, user, Set((newRoadNumber, newRoadPartNumber)))
        None
      }
    } catch {
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  /**
    * Will attempt to find relevant sideCode information to the projectLinks given a number of factors
    * for example if they are of suravage or complementary origin
    *
    * @param linkIds the linkIds to process
    * @param roadNumber the roadNumber to apply/was applied to said linkIds
    * @param roadPartNumber the roadPartNumber to apply/was applied to said linkIds
    * @param newLinks new project links for this ramp
    * @return the projectLinks with a assigned SideCode
    */
  private def fillRampGrowthDirection(linkIds: Set[Long], roadNumber: Long, roadPartNumber: Long,
                                      newLinks: Seq[ProjectLink], firstLinkId: Long, existingLinks: Seq[ProjectLink]): Seq[ProjectLink] = {
    if (newLinks.exists(nl => existingLinks.exists(pl => pl.status != LinkStatus.Terminated &&
      GeometryUtils.areAdjacent(pl.geometry, nl.geometry)))) {
      // Connected to existing geometry -> let the track section calculation take it's natural course
      newLinks.map(_.copy(sideCode = SideCode.Unknown))
    } else {
      val roadLinks = roadLinkService.fetchVVHRoadlinksAndComplementary(linkIds) ++ roadLinkService.fetchSuravageLinksByLinkIdsFromVVH(linkIds)
      //Set the sideCode as defined by the trafficDirection
      val sideCode = roadLinks.map(rl => rl.linkId -> (rl.trafficDirection match {
        case TrafficDirection.AgainstDigitizing => SideCode.AgainstDigitizing
        case TrafficDirection.TowardsDigitizing => SideCode.TowardsDigitizing
        case _ => SideCode.Unknown
      })).toMap
      newLinks.map(nl => nl.copy(sideCode = sideCode.getOrElse(nl.linkId, SideCode.Unknown)))
    }
  }

  def getFirstProjectLink(project: RoadAddressProject): Option[ProjectLink] = {
    project.reservedParts.find(_.startingLinkId.nonEmpty) match {
      case Some(rrp) =>
        withDynSession {
          ProjectDAO.fetchFirstLink(project.id, rrp.roadNumber, rrp.roadPartNumber)
        }
      case _ => None
    }
  }

  def changeDirection(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Seq[LinkToRevert], username: String): Option[String] = {
    RoadAddressLinkBuilder.municipalityRoadMaintainerMapping // make sure it is populated outside of this TX
    try {
      withDynTransaction {
        if (ProjectDAO.countLinksUnchangedUnhandled(projectId, roadNumber, roadPartNumber) > 0)
          return Some(ErrorReversingUnchangedLinks)
        val continuity = ProjectDAO.getContinuityCodes(projectId, roadNumber, roadPartNumber)
        val newContinuity: Map[Long, Discontinuity] = if (continuity.nonEmpty) {
          val discontinuityAtEnd = continuity.maxBy(_._1)
          continuity.filterKeys(_ < discontinuityAtEnd._1).map{ case (addr, d) => (discontinuityAtEnd._1 - addr) -> d} ++
            Map(discontinuityAtEnd._1 -> discontinuityAtEnd._2)
        } else
          Map()
        ProjectDAO.reverseRoadPartDirection(projectId, roadNumber, roadPartNumber)
        val projectLinks = ProjectDAO.getProjectLinks(projectId).filter(_.status != LinkStatus.Terminated)
        val originalSideCodes = RoadAddressDAO.fetchByIdMassQuery(projectLinks.map(_.roadAddressId).toSet, true, true)
          .map(ra => ra.id -> ra.sideCode).toMap

        ProjectDAO.updateProjectLinksToDB(projectLinks.map(x =>
          x.copy(reversed = isReversed(originalSideCodes)(x),
            discontinuity = newContinuity.getOrElse(x.endAddrMValue, Discontinuity.Continuous))),username)
        recalculateProjectLinks(projectId, username, Set((roadNumber, roadPartNumber)))
        None
      }
    } catch {
      case NonFatal(e) =>
        logger.info("Direction change failed", e)
        Some(ErrorSavingFailed)
    }
  }

  private def isReversed(originalSideCodes: Map[Long, SideCode])(projectLink: ProjectLink): Boolean ={
    originalSideCodes.get(projectLink.roadAddressId) match {
      case Some(sideCode) if sideCode != projectLink.sideCode => true
      case _ => false
    }
  }

  /**
    * Adds reserved road links (from road parts) to a road address project. Clears
    * project links that are no longer reserved for the project. Reservability is check before this.
    */
  private def addLinksToProject(project: RoadAddressProject): Option[String] = {
    //TODO: Check that there are no floating road addresses present when starting
    logger.info(s"Adding reserved road parts with links to project ${project.id}")
    val projectLinks = ProjectDAO.getProjectLinks(project.id)
    logger.debug(s"Links fetched")
    project.reservedParts.foreach(p => logger.debug(s"Project has part ${p.roadNumber}/${p.roadPartNumber} in ${p.ely} (${p.addressLength} m)"))
    val projectLinkOriginalParts = RoadAddressDAO.queryById(projectLinks.map(_.roadAddressId).toSet).map(ra => (ra.roadNumber, ra.roadPartNumber)).toSet
    validateReservations(project.reservedParts, project.ely, project.id, projectLinks) match {
      case Some(error) => throw new RoadPartReservedException(error)
      case None => logger.debug(s"Validation passed")
        val addresses = project.reservedParts.filterNot(res =>
          projectLinkOriginalParts.contains((res.roadNumber, res.roadPartNumber))).flatMap { reservation =>
          logger.debug(s"Reserve $reservation")
          val addressesOnPart = RoadAddressDAO.fetchByRoadPart(reservation.roadNumber, reservation.roadPartNumber, false)
          val mapping = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(addressesOnPart.map(_.linkId).toSet, false, useFrozenVVHLinks)
            .map(rl => rl.linkId -> rl).toMap
          val reserved = checkAndReserve(project, reservation)
          if (reserved._1.isEmpty)
            throw new RuntimeException(s"Tie ${reservation.roadNumber} osa ${reservation.roadPartNumber} ei ole vapaana projektin alkupäivämääränä. Tieosoite on jo varattuna toisessa projektissa ${reserved._2.get}.")
          addressesOnPart.map(ra => newProjectTemplate(mapping(ra.linkId), ra, project))
        }
        logger.debug(s"Reserve done")
        val linksOnRemovedParts = projectLinks.filterNot(pl => project.reservedParts.exists(_.holds(pl)))
        val newProjectLinks = addresses.filterNot {
          ad => projectLinks.exists(pl => pl.roadNumber == ad.roadNumber && pl.roadPartNumber == ad.roadPartNumber)
        }
        logger.debug(s"Removed / new links ready")
        if (linksOnRemovedParts.nonEmpty) {
          ProjectDAO.removeProjectLinksById(linksOnRemovedParts.map(_.id).toSet)
        }
        logger.debug(s"Removed deleted ${linksOnRemovedParts.size}")
        ProjectDAO.create(newProjectLinks)
        logger.debug(s"New links created ${newProjectLinks.size}")
        if (project.ely.isEmpty) {
          ProjectDAO.fetchReservedRoadParts(project.id).find(_.ely.nonEmpty).flatMap(_.ely).foreach(ely =>
            ProjectDAO.updateProjectEly(project.id, ely))
        }
        logger.info(s"Adding reserved road parts finished for project ${project.id}")
        None
    }
  }

  private def validateReservations(reservedRoadParts: Seq[ReservedRoadPart], projectEly: Option[Long], projectId: Long,
                                   projectLinks: Seq[ProjectLink]): Option[String] = {
    val errors = reservedRoadParts.flatMap{part =>
      val roadPartExistsInAddresses = RoadAddressDAO.roadPartExists(part.roadNumber, part.roadPartNumber) ||
        ProjectDAO.fetchProjectLinkIds(projectId, part.roadNumber, part.roadPartNumber, None, Some(1)).nonEmpty
      if (!roadPartExistsInAddresses) {
        Some(s"TIE ${part.roadNumber} OSA: ${part.roadPartNumber}")
      } else
        None
    }
    val elyErrors = reservedRoadParts.flatMap(roadAddress =>
      if (projectEly.filterNot(l => l == -1L).getOrElse(roadAddress.ely.get) != roadAddress.ely.get) {
        Some(s"TIE ${roadAddress.roadNumber} OSA: ${roadAddress.roadPartNumber} (ELY ${roadAddress.ely} != ${projectEly.get})")
      } else None)
    if (errors.nonEmpty)
      Some(s"$ErrorFollowingRoadPartsNotFoundInDB ${errors.mkString(", ")}")
    else {
      if (elyErrors.nonEmpty)
        Some(s"$ErrorFollowingPartsHaveDifferingEly ${elyErrors.mkString(", ")}")
      else {
        val ely = reservedRoadParts.map(_.ely)
        if (ely.distinct.size > 1) {
          Some(ErrorRoadPartsHaveDifferingEly)
        } else {
          None
        }
      }
    }
  }

  def revertSplit(projectId: Long, linkId: Long, userName: String): Option[String] = {
    withDynTransaction{
      val previousSplit = ProjectDAO.fetchSplitLinks(projectId, linkId)
      if (previousSplit.nonEmpty) {
        revertSplitInTX(projectId, previousSplit, userName)
        None
      } else
        Some(s"No split for link id $linkId found!")
    }
  }

  def revertSplitInTX(projectId: Long, previousSplit: Seq[ProjectLink], userName: String): Unit = {

    def getGeometryWithTimestamp(linkId: Long, timeStamp: Long, roadLinks: Seq[RoadLink],
                                 vvhHistoryLinks: Seq[VVHHistoryRoadLink]): Seq[Point] = {
      val matchingLinksGeometry = (roadLinks ++ vvhHistoryLinks).find(rl => rl.linkId == linkId && rl.vvhTimeStamp == timeStamp).map(_.geometry)
      if (matchingLinksGeometry.nonEmpty) {
        matchingLinksGeometry.get
      } else {
        (roadLinks ++ vvhHistoryLinks).find(rl => rl.linkId == linkId).map(_.geometry)
          .getOrElse(throw new InvalidAddressDataException(s"Geometry with linkId $linkId and timestamp $timeStamp not found!"))
      }
    }

    val (roadLinks, vvhHistoryLinks) = roadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(previousSplit.map(_.linkId).toSet, useFrozenVVHLinks)
    val (suravage, original) = previousSplit.partition(_.linkGeomSource == LinkGeomSource.SuravageLinkInterface)
    revertLinks(projectId, previousSplit.head.roadNumber, previousSplit.head.roadPartNumber,
      suravage.map(link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
      original.map(link => LinkToRevert(link.id, link.linkId, link.status.value, getGeometryWithTimestamp(link.linkId,
        link.linkGeometryTimeStamp, roadLinks, vvhHistoryLinks))),
      userName, false)
  }

  def preSplitSuravageLink(linkId: Long, userName: String, splitOptions: SplitOptions): (Option[Seq[ProjectLink]], Seq[ProjectLink], Option[String], Option[(Point, Vector3d)]) = {
    def previousSplitToSplitOptions(plSeq: Seq[ProjectLink], splitOptions: SplitOptions): SplitOptions = {
      val splitsAB = plSeq.filter(_.linkId == linkId)
      val (template, splitA, splitB) = (plSeq.find(_.status == LinkStatus.Terminated),
        splitsAB.find(_.status != LinkStatus.New), splitsAB.find(_.status == LinkStatus.New))
      val linkData = splitA.orElse(splitB).orElse(template).map(l => (l.roadNumber, l.roadPartNumber,
        l.track, l.ely)).getOrElse((splitOptions.roadNumber, splitOptions.roadPartNumber, splitOptions.trackCode, splitOptions.ely))
      val discontinuity = splitsAB.filterNot(_.discontinuity == Continuous).map(_.discontinuity).headOption
      splitOptions.copy(statusA = splitA.map(_.status).getOrElse(splitOptions.statusA),
        roadNumber = linkData._1, roadPartNumber = linkData._2, trackCode = linkData._3, ely = linkData._4,
        discontinuity = discontinuity.getOrElse(Continuous))
    }

    withDynTransaction {
      val previousSplit = ProjectDAO.fetchSplitLinks(splitOptions.projectId, linkId)
      val updatedSplitOptions =
        if (previousSplit.nonEmpty) {
          previousSplitToSplitOptions(previousSplit, splitOptions)
        } else
          splitOptions
      val r = preSplitSuravageLinkInTX(linkId, userName, updatedSplitOptions)
      dynamicSession.rollback()
      (r._1.map(rs => rs.toSeqWithMergeTerminated), r._1.get.allTerminatedProjectLinks, r._2, r._3)
    }
  }

  def splitSuravageLink(linkId: Long, username: String,
                        splitOptions: SplitOptions): Option[String] = {
    withDynTransaction {
      splitSuravageLinkInTX(linkId, username, splitOptions)
    }
  }

  def preSplitSuravageLinkInTX(linkId: Long, username: String,
                               splitOptions: SplitOptions): (Option[SplitResult], Option[String], Option[(Point, Vector3d)]) = {
    val projectId = splitOptions.projectId
    val sOption = roadLinkService.getSuravageRoadLinksByLinkIdsFromVVH(Set(Math.abs(linkId)), false).headOption
    val previousSplit = ProjectDAO.fetchSplitLinks(projectId, linkId)
    val project = ProjectDAO.getRoadAddressProjectById(projectId).get
    if (sOption.isEmpty) {
      (None, Some(ErrorSuravageLinkNotFound), None)
    } else {
      if (previousSplit.nonEmpty)
        revertSplitInTX(projectId, previousSplit, username)
      val suravageLink = sOption.get
      val endPoints = GeometryUtils.geometryEndpoints(suravageLink.geometry)
      val x = if (endPoints._1.x > endPoints._2.x) (endPoints._2.x, endPoints._1.x) else (endPoints._1.x, endPoints._2.x)
      val rightTop = Point(x._2, endPoints._2.y)
      val leftBottom = Point(x._1, endPoints._1.y)
      val projectLinks = getProjectLinksInBoundingBox(BoundingRectangle(leftBottom, rightTop), projectId)
      if (projectLinks.isEmpty)
        return (None, Some(ErrorNoMatchingProjectLinkForSplit), None)
      val roadLink = roadLinkService.getRoadLinkByLinkIdFromVVH(projectLinks.head.linkId, false).headOption
      if (roadLink.isEmpty) {
        (None, Some(ErrorSuravageLinkNotFound), None)
      }

      val projectLinksConnected = projectLinks.filter(l => GeometryUtils.areAdjacent(l.geometry, suravageLink.geometry))

      //we rank template links near suravage link by how much they overlap with suravage geometry
      val commonSections = projectLinksConnected.map(x =>
        x -> ProjectLinkSplitter.findMatchingGeometrySegment(suravageLink, x).map(GeometryUtils.geometryLength)
          .getOrElse(0.0)).filter(_._2 > MinAllowedRoadAddressLength)
      if (commonSections.isEmpty)
        (None, Some(ErrorNoMatchingProjectLinkForSplit), None)
      else {
        val bestFit = commonSections.maxBy(_._2)._1
        val splitResult = ProjectLinkSplitter.split(roadLink.get, newProjectLink(suravageLink, project, splitOptions), bestFit, projectLinks, splitOptions)
        (Some(splitResult), None, GeometryUtils.calculatePointAndHeadingOnGeometry(suravageLink.geometry, splitOptions.splitPoint))
      }
    }
  }

  def splitSuravageLinkInTX(linkId: Long, username: String, splitOptions: SplitOptions): Option[String] = {
    val (splitResultOption, errorMessage, _) = preSplitSuravageLinkInTX(linkId, username, splitOptions)
    if(errorMessage.nonEmpty){
      errorMessage
    } else {
      splitResultOption.map{
        splitResult =>
          val splitLinks = splitResult.toSeqWithAllTerminated
          ProjectDAO.removeProjectLinksByLinkId(splitOptions.projectId, splitLinks.map(_.linkId).toSet)
          ProjectDAO.create(splitLinks.map(x => x.copy(modifiedBy = Some(username))))
          ProjectDAO.updateProjectCoordinates(splitOptions.projectId, splitOptions.coordinates)
          recalculateProjectLinks(splitOptions.projectId, username, Set((splitOptions.roadNumber, splitOptions.roadPartNumber)))
      }
      None
    }
  }

  def getProjectLinksInBoundingBox(bbox: BoundingRectangle, projectId: Long): (Seq[ProjectLink]) = {
    val roadLinks = roadLinkService.getRoadLinksWithComplementaryFromVVH(bbox, Set(), false).map(rl => rl.linkId -> rl).toMap
    ProjectDAO.getProjectLinksByProjectAndLinkId(roadLinks.keys, projectId).filter(_.status == LinkStatus.NotHandled)
  }

  /**
    * Save road link project, reserve new road parts, free previously reserved road parts that were removed
    *
    * @param roadAddressProject Updated road address project case class
    * @return Updated project reloaded from the database
    */
  def saveProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (projectFound(roadAddressProject).isEmpty)
      throw new IllegalArgumentException("Project not found")
    withDynTransaction {
      val storedProject = ProjectDAO.getRoadAddressProjectById(roadAddressProject.id).get
      val removed = storedProject.reservedParts.filterNot(part =>
        roadAddressProject.reservedParts.exists(rp => rp.roadPartNumber == part.roadPartNumber &&
          rp.roadNumber == part.roadNumber))
      removed.foreach(p => ProjectDAO.removeReservedRoadPart(roadAddressProject.id, p))
      addLinksToProject(roadAddressProject)
      val updatedProject = ProjectDAO.getRoadAddressProjectById(roadAddressProject.id).get
      if (updatedProject.reservedParts.nonEmpty) {
        ProjectDAO.updateRoadAddressProject(roadAddressProject.copy(ely = ProjectDAO.getElyFromProjectLinks(roadAddressProject.id)))
      }
      else //in empty case we release ely
        ProjectDAO.updateRoadAddressProject(roadAddressProject.copy(ely = None))
      ProjectDAO.getRoadAddressProjectById(roadAddressProject.id).get
    }
  }

  /**
    * Delete road link project, if it exists and the state is Incomplete
    *
    * @param projectId Id of the project to delete
    * @return boolean that confirms if the project is deleted
    */
  def deleteProject(projectId : Long) = {
    withDynTransaction {
      val project = ProjectDAO.getRoadAddressProjectById(projectId)
      val canBeDeleted = projectId != 0 && project.isDefined && project.get.status == ProjectState.Incomplete
      if(canBeDeleted) {
        ProjectDAO.removeProjectLinksByProject(projectId)
        ProjectDAO.removeReservedRoadPartsByProject(projectId)
        ProjectDAO.updateProjectStatus(projectId, ProjectState.Deleted)
        ProjectDAO.updateProjectStateInfo(ProjectState.Deleted.description, projectId)
      }
      canBeDeleted
    }
  }

  def createRoadLinkProject(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    withDynTransaction {
      createProject(roadAddressProject)
    }
  }

  def getRoadAddressSingleProject(projectId: Long): Option[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects(projectId).headOption
    }
  }

  def getRoadAddressAllProjects(): Seq[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects()
    }
  }

  def updateProjectLinkGeometry(projectId:Long, username:String, onlyNotHandled:Boolean =false): Unit = {
    withDynTransaction {
      val projectLinks = ProjectDAO.getProjectLinks(projectId, if (onlyNotHandled) Some(LinkStatus.NotHandled) else None)
      val roadLinks = roadLinkService.getCurrentAndComplementaryVVHRoadLinks(projectLinks.filter(x=>x.linkGeomSource==LinkGeomSource.NormalLinkInterface
        || x.linkGeomSource==LinkGeomSource.FrozenLinkInterface || x.linkGeomSource==LinkGeomSource.ComplimentaryLinkInterface).map(x=>x.linkId).toSet)
      val suravageLinks=roadLinkService.fetchSuravageLinksByLinkIdsFromVVH(projectLinks.filter(x=>x.linkGeomSource==LinkGeomSource.SuravageLinkInterface).map(x=>x.linkId).toSet)
      val vvhLinks=roadLinks++suravageLinks
      val geometryMap = vvhLinks.map(l => l.linkId -> (l.geometry, l.vvhTimeStamp)).toMap
      val timeStamp = new Date().getTime
      val updatedProjectLinks = projectLinks.map { pl =>
        val (geometry, time) = geometryMap.getOrElse(pl.linkId, (Seq(), timeStamp))
        pl.copy(geometry = GeometryUtils.truncateGeometry2D(geometry, pl.startMValue, pl.endMValue),
          linkGeometryTimeStamp = time)
      }
      ProjectDAO.updateProjectLinksGeometry(updatedProjectLinks, username)
    }
  }

  /**
    * Check that road part is available for reservation and return the id of reserved road part table row.
    * Reservation must contain road number and road part number, other data is not used or saved.
    *
    * @param project          Project for which to reserve (or for which it is already reserved)
    * @param reservedRoadPart Reservation information (req: road number, road part number)
    * @return
    */
  private def checkAndReserve(project: RoadAddressProject, reservedRoadPart: ReservedRoadPart): (Option[ReservedRoadPart], Option[String]) = {
    logger.info(s"Check ${project.id} matching to " + ProjectDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber))
    ProjectDAO.roadPartReservedTo(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber) match {
      case Some(proj) if proj._1 != project.id => (None, Some(proj._2))
      case Some(proj) if proj._1 == project.id =>
        (ProjectDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber), None)
      case _ =>
        ProjectDAO.reserveRoadPart(project.id, reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber, project.modifiedBy)
        (ProjectDAO.fetchReservedRoadPart(reservedRoadPart.roadNumber, reservedRoadPart.roadPartNumber), None)
    }
  }

  def getProjectLinksWithSuravage(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                                  roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                  publicRoads: Boolean=false): Seq[ProjectAddressLink] ={
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    val suravageList = Await.result(fetch.suravageF, Duration.Inf).map(l => RoadAddressLinkBuilder.buildSuravageRoadAddressLink(l))
    val projectLinks = fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, useFrozenVVHLinks, fetch)
    val keptSuravageLinks = suravageList.filter(sl => !projectLinks.exists(pl => sl.linkId == pl.linkId))
    keptSuravageLinks.map(ProjectAddressLinkBuilder.build) ++
      projectLinks
  }

  def getProjectLinksLinear(roadAddressService: RoadAddressService, projectId: Long, boundingRectangle: BoundingRectangle,
                                  roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false,
                                  publicRoads: Boolean=false): Seq[ProjectAddressLink] ={
    val projectLinks = fetchProjectRoadLinksLinearGeometry(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, useFrozenVVHLinks)
      projectLinks
  }

  def getChangeProject(projectId: Long): Option[ChangeProject] = {
    val changeProjectData = withDynTransaction {
      try {
        if (recalculateChangeTable(projectId)) {
          val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(Set(projectId))
          Some(ViiteTierekisteriClient.convertToChangeProject(roadAddressChanges))
        } else {
          None
        }
      } catch {
        case NonFatal(e) =>
          logger.info(s"Change info not available for project $projectId: " + e.getMessage)
          None
      }
    }
    changeProjectData
  }

  def getRoadAddressChangesAndSendToTR(projectId: Set[Long]) = {
    val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(projectId)
    ViiteTierekisteriClient.sendChanges(roadAddressChanges)
  }

  def getProjectRoadLinksByLinkIds(linkIdsToGet: Set[Long], newTransaction: Boolean = true): Seq[ProjectAddressLink] = {

    if (linkIdsToGet.isEmpty)
      return Seq()

    val fetchVVHStartTime = System.currentTimeMillis()
    val complementedRoadLinks = roadLinkService.getViiteRoadLinksByLinkIdsFromVVH(linkIdsToGet, newTransaction, useFrozenVVHLinks)
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val projectRoadLinks = complementedRoadLinks
      .map { rl =>
        val ra = Seq()
        val missed = Seq()
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val filledProjectLinks = RoadAddressFiller.fillTopology(complementedRoadLinks, projectRoadLinks)

    filledProjectLinks._1.map(ProjectAddressLinkBuilder.build)
  }

  def getProjectSuravageRoadLinksByLinkIds(linkIdsToGet: Set[Long]): Seq[ProjectAddressLink] = {
    if (linkIdsToGet.isEmpty)
      Seq()
    else {
      val fetchVVHStartTime = System.currentTimeMillis()
      val suravageRoadLinks = roadAddressService.getSuravageRoadLinkAddressesByLinkIds(linkIdsToGet)
      val fetchVVHEndTime = System.currentTimeMillis()
      logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))
      suravageRoadLinks.map(ProjectAddressLinkBuilder.build)
    }
  }

  def fetchProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                            everything: Boolean = false, publicRoads: Boolean = false, fetch: ProjectBoundingBoxResult): Seq[ProjectAddressLink] = {
    def complementaryLinkFilter(roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                everything: Boolean = false, publicRoads: Boolean = false)(roadAddressLink: RoadAddressLink) = {
      everything || publicRoads || roadNumberLimits.exists {
        case (start, stop) => roadAddressLink.roadNumber >= start && roadAddressLink.roadNumber <= stop
      }
    }

    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false,
        roadNumberLimits = roadNumberLimits).partition(_.floating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })
    val fetchProjectLinksF = fetch.projectLinkResultF
    val fetchVVHStartTime = System.currentTimeMillis()

    val (regularLinks, complementaryLinks, suravageLinks) = awaitRoadLinks(fetch.roadLinkF, fetch.complementaryF, fetch.suravageF)
    val linkIds = regularLinks.map(_.linkId).toSet ++ complementaryLinks.map(_.linkId).toSet ++ suravageLinks.map(_.linkId).toSet
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val fetchMissingRoadAddressStartTime = System.currentTimeMillis()
    val ((floating, addresses), projectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)

    val normalLinks = regularLinks.filterNot(l => projectLinks.keySet.contains(l.linkId))

    val missedRL = if(useFrozenVVHLinks) {
      Map[Long, Seq[MissingRoadAddress]]()
    } else {
      withDynTransaction {
        val missingLinkIds = linkIds -- floating.keySet -- addresses.keySet -- projectLinks.keySet
        RoadAddressDAO.getMissingRoadAddresses(missingLinkIds)
      }
    }.groupBy(_.linkId)
    val fetchMissingRoadAddressEndTime = System.currentTimeMillis()
    logger.info("End fetch missing and floating road address in %.3f sec".format((fetchMissingRoadAddressEndTime - fetchMissingRoadAddressStartTime) * 0.001))

    val buildStartTime = System.currentTimeMillis()

    val projectRoadLinks = projectLinks.map {
      pl =>pl._1 -> buildProjectRoadLink(pl._2)
    }

    val nonProjectRoadLinks = (normalLinks ++ complementaryLinks).filterNot(rl => projectRoadLinks.keySet.contains(rl.linkId))

    val nonProjectTopology = nonProjectRoadLinks
      .map { rl =>
        val ra = addresses.getOrElse(rl.linkId, Seq())
        val missed = missedRL.getOrElse(rl.linkId, Seq())
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val buildEndTime = System.currentTimeMillis()
    logger.info("End building road address in %.3f sec".format((buildEndTime - buildStartTime) * 0.001))

    val (filledTopology, _) = RoadAddressFiller.fillTopology(nonProjectRoadLinks, nonProjectTopology)

    val complementaryLinkIds = complementaryLinks.map(_.linkId).toSet
    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
      complementaryLinkFilter(roadNumberLimits, municipalities, everything, publicRoads)(link))
    if (useFrozenVVHLinks) {
      returningTopology.filter(link => link.anomaly != Anomaly.NoAddressGiven).map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
    } else {
      returningTopology.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
    }
  }

  def fetchProjectRoadLinksLinearGeometry(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                            everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {

    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false,
        roadNumberLimits = roadNumberLimits).partition(_.floating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })

    val fetchProjectLinksF = Future(withDynSession(ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId)))
    val fetchStartTime = System.currentTimeMillis()
    val ((floating, addresses), projectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)

    val fetchEndTime = System.currentTimeMillis()
    logger.info("fetch time: %.3f sec ".format((fetchEndTime - fetchStartTime) * 0.001))

    val buildStartTime = System.currentTimeMillis()
    val projectRoadLinks = projectLinks.map {
      pl =>pl._1 -> buildProjectRoadLink(pl._2)
    }

    val nonProjecAddresses = addresses.filterNot(a=> projectLinks.contains(a._1))

    val nonProjectLinks = nonProjecAddresses.values.flatten.toSeq.map { address =>
      address.linkId -> RoadAddressLinkBuilder.buildSimpleLink(address)
    }.toMap

    val buildEndTime = System.currentTimeMillis()
    logger.info("End building road address in %.3f sec".format((buildEndTime - buildStartTime) * 0.001))


    if (useFrozenVVHLinks) {
      nonProjectLinks.values.toSeq.filter(link => link.anomaly != Anomaly.NoAddressGiven).map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
    } else {
      nonProjectLinks.values.toSeq.map(ProjectAddressLinkBuilder.build) ++ projectRoadLinks.values.flatten
    }
  }


  def fetchBoundingBoxF(boundingRectangle: BoundingRectangle, projectId: Long, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                        everything: Boolean = false, publicRoads: Boolean = false): ProjectBoundingBoxResult = {
    ProjectBoundingBoxResult(
      Future(withDynSession(ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId))),
      Future(roadLinkService.getViiteRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything,
        publicRoads, useFrozenVVHLinks)),
      Future(
        if (everything) roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities)
        else Seq()),
      roadLinkService.getSuravageLinksFromVVHF(boundingRectangle, municipalities)
    )
  }

  def getProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    val fetch = fetchBoundingBoxF(boundingRectangle, projectId, roadNumberLimits, municipalities, everything, publicRoads)
    fetchProjectRoadLinks(projectId, boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads, fetch)
  }

  private def getProjectWithReservationChecks(projectId: Long, newRoadNumber: Long, newRoadPart: Long): RoadAddressProject = {
    RoadAddressValidator.checkProjectExists(projectId)
    val project = ProjectDAO.getRoadAddressProjectById(projectId).get
    RoadAddressValidator.checkAvailable(newRoadNumber, newRoadPart, project)
    RoadAddressValidator.checkNotReserved(newRoadNumber, newRoadPart, project)
    project
  }

  def revertLinks(links: Iterable[ProjectLink], userName: String): Option[String] = {
    if (links.groupBy(l => (l.projectId, l.roadNumber, l.roadPartNumber)).keySet.size != 1)
      throw new IllegalArgumentException("Reverting links from multiple road parts at once is not allowed")
    val l = links.head
    revertLinks(l.projectId, l.roadNumber, l.roadPartNumber, links.map(
      link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)), userName)
  }

  private def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, toRemove: Iterable[LinkToRevert],
                          modified: Iterable[LinkToRevert], userName: String, recalculate: Boolean = true): Unit = {
    ProjectDAO.removeProjectLinksByLinkId(projectId, toRemove.map(_.linkId).toSet)
    RoadAddressDAO.fetchByLinkId(modified.map(_.linkId).toSet).foreach(ra =>
      modified.find(mod => mod.linkId == ra.linkId) match {
        case Some(mod) if mod.geometry.nonEmpty =>
          val geom = GeometryUtils.truncateGeometry3D(mod.geometry, ra.startMValue, ra.endMValue)
          ProjectDAO.updateProjectLinkValues(projectId, ra.copy(geometry = geom))
        case _ => ProjectDAO.updateProjectLinkValues(projectId, ra, updateGeom = false)
      })
    if (recalculate)
      try {
        recalculateProjectLinks(projectId, userName, Set((roadNumber, roadPartNumber)))
      } catch {
        case ex: Exception => logger.info("Couldn't recalculate after reverting a link (this may happen)")
      }
    val afterUpdateLinks = ProjectDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
    if (afterUpdateLinks.isEmpty){
      releaseRoadPart(projectId, roadNumber, roadPartNumber, userName)
    }
  }

  def isProjectWithGivenLinkIdWritable(linkId: Long): Boolean = {
    val projects =
      withDynSession(ProjectDAO.getProjectsWithGivenLinkId(linkId))
    if (projects.isEmpty)
      return false
    true
  }


  def revertLinks(projectId: Long, roadNumber: Long, roadPartNumber: Long, links: Iterable[LinkToRevert], userName: String): Option[String] = {
    try {
      withDynTransaction {
        val (added, modified) = links.partition(_.status == LinkStatus.New.value)
        if (modified.exists(_.status == LinkStatus.Numbering.value)) {
          logger.info(s"Reverting whole road part in $projectId ($roadNumber/$roadPartNumber)")
          // Numbering change affects the whole road part
          revertLinks(projectId, roadNumber, roadPartNumber, added,
            ProjectDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId).map(
              link => LinkToRevert(link.id, link.linkId, link.status.value, link.geometry)),
            userName)
        } else {
          revertLinks(projectId, roadNumber, roadPartNumber, added, modified, userName)
        }
        None
      }
    }
    catch {
      case NonFatal(e) =>
        logger.info("Error reverting the changes on roadlink", e)
        Some("Virhe tapahtui muutosten palauttamisen yhteydessä")
    }
  }

  private def releaseRoadPart(projectId: Long, roadNumber: Long, roadPartNumber: Long, userName: String) = {
    if (ProjectDAO.fetchFirstLink(projectId, roadNumber, roadPartNumber).isEmpty) {
      val part = ProjectDAO.fetchReservedRoadPart(roadNumber, roadPartNumber).get
      ProjectDAO.removeReservedRoadPart(projectId, part)
    } else {
      val links = ProjectDAO.fetchByProjectRoadPart(roadNumber, roadPartNumber, projectId)
      val part = ProjectDAO.fetchReservedRoadPart(roadNumber, roadPartNumber).get
      revertLinks(links, userName)
    }
  }

  /**
    * Update project links to given status and recalculate delta and change table
    *
    * @param projectId  Project's id
    * @param linkIds    Set of link ids that are set to this status
    * @param linkStatus New status for given link ids
    * @param userName   Username of the user that does this change
    * @return true, if the delta calculation is successful and change table has been updated.
    */
  def updateProjectLinks(projectId: Long, linkIds: Set[Long], linkStatus: LinkStatus, userName: String,
                         newRoadNumber: Long, newRoadPartNumber: Long, newTrackCode: Int,
                         userDefinedEndAddressM: Option[Int], roadType: Long = RoadType.PublicRoad.value,
                         discontinuity: Int = Discontinuity.Continuous.value, ely: Option[Long] = None,
                         reversed: Boolean = false): Option[String] = {

    def updateRoadTypeDiscontinuity(links: Seq[ProjectLink]) = {
      if (links.nonEmpty) {
        val lastSegment = links.maxBy(_.endAddrMValue)
        if (links.size > 1) {
          val linksToUpdate = links.filterNot(_.id == lastSegment.id)
          ProjectDAO.updateProjectLinksToDB(linksToUpdate, userName)
        }
        ProjectDAO.updateProjectLinksToDB(Seq(lastSegment.copy(discontinuity = Discontinuity.apply(discontinuity.toInt))), userName)
      }
    }

    def checkAndMakeReservation(linkEly: Long) = {
      val project = getProjectWithReservationChecks(projectId, newRoadNumber, newRoadPartNumber)
      if (!project.isReserved(newRoadNumber, newRoadPartNumber))
        ProjectDAO.reserveRoadPart(project.id, newRoadNumber, newRoadPartNumber, project.modifiedBy)
    }

    def resetLinkValues(toReset: Seq[ProjectLink]) = {
      RoadAddressDAO.queryById(toReset.map(_.roadAddressId).toSet).foreach(ra =>
        ProjectDAO.updateProjectLinkValues(projectId, ra, updateGeom = false))
    }

    try {
      withDynTransaction {
        val toUpdateLinks = ProjectDAO.getProjectLinksByProjectAndLinkId(linkIds, projectId)
        if (toUpdateLinks.exists(_.isSplit))
          throw new ProjectValidationException(ErrorSplitSuravageNotUpdatable)
        userDefinedEndAddressM.map(addressM => {
          val endSegment = toUpdateLinks.maxBy(_.endAddrMValue)
          val calibrationPoint = UserDefinedCalibrationPoint(newCalibrationPointId, endSegment.id, projectId, endSegment.endMValue, addressM)
          // TODO: remove calibration points that exist elsewhere except at the link end or start
          val foundCalibrationPoint = CalibrationPointDAO.findCalibrationPointByRemainingValues(endSegment.id, projectId, endSegment.endMValue)
          if (foundCalibrationPoint.isEmpty)
            CalibrationPointDAO.createCalibrationPoint(calibrationPoint)
          else
            CalibrationPointDAO.updateSpecificCalibrationPointMeasures(foundCalibrationPoint.head.id, endSegment.endMValue, addressM)
          Seq(CalibrationPoint)
        })
        linkStatus match {
          case LinkStatus.Terminated => {
            // Fetching road addresses in order to obtain the original addressMValues, since we may not have those values
            // on project_link table, after previous recalculations
            resetLinkValues(toUpdateLinks)
            ProjectDAO.updateProjectLinksToTerminated(toUpdateLinks.map(_.id).toSet, userName)
          }
          case LinkStatus.Numbering => {
            if (toUpdateLinks.nonEmpty) {
              val roadAddresses = RoadAddressDAO.fetchByIdMassQuery(toUpdateLinks.map(_.roadAddressId).toSet, includeFloating = true)
              if (roadAddresses.exists(x =>
                x.roadNumber == newRoadNumber && x.roadPartNumber == newRoadPartNumber)) // check the original numbering wasn't exactly the same
                throw new ProjectValidationException(ErrorRenumberingToOriginalNumber) // you cannot use current roadnumber and roadpart number in numbering operation
              if (toUpdateLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).distinct.size != 1 ||
                roadAddresses.map(ra => (ra.roadNumber, ra.roadPartNumber)).distinct.size != 1) {
                throw new ProjectValidationException(ErrorMultipleRoadNumbersOrParts)
              }
              //TODO: Check that the numbering target road number + road part does not exist or is reserved to this project
              checkAndMakeReservation(toUpdateLinks.head.ely)
              ProjectDAO.updateProjectLinkNumbering(projectId, toUpdateLinks.head.roadNumber, toUpdateLinks.head.roadPartNumber,
                linkStatus, newRoadNumber, newRoadPartNumber, userName)
            } else {
              throw new ProjectValidationException(ErrorRoadLinkNotFoundInProject)
            }
          }
          case LinkStatus.Transfer => {
            checkAndMakeReservation(toUpdateLinks.head.ely)
            val updated = toUpdateLinks.map(l => {
              l.copy(roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode),
                status = linkStatus, calibrationPoints = (None, None), roadType = RoadType.apply(roadType.toInt))
            })
            ProjectDAO.updateProjectLinksToDB(updated, userName)
            ProjectDAO.updateProjectLinkRoadTypeDiscontinuity(Set(updated.maxBy(_.endAddrMValue).id), linkStatus, userName, roadType, Some(discontinuity))
          }
          case LinkStatus.UnChanged => {
            checkAndMakeReservation(toUpdateLinks.head.ely)
            // Reset back to original values
            resetLinkValues(toUpdateLinks)
            updateRoadTypeDiscontinuity(toUpdateLinks.map(_.copy(roadType = RoadType.apply(roadType.toInt), status = linkStatus)))
          }
          case LinkStatus.New => {
            checkAndMakeReservation(toUpdateLinks.head.ely)
            updateRoadTypeDiscontinuity(toUpdateLinks.map(_.copy(roadType = RoadType.apply(roadType.toInt), roadNumber = newRoadNumber, roadPartNumber = newRoadPartNumber, track = Track.apply(newTrackCode))))
          }
          case _ =>
            throw new ProjectValidationException(s"Virheellinen operaatio $linkStatus")
        }
        recalculateProjectLinks(projectId, userName, Set((newRoadNumber, newRoadPartNumber)) ++
          toUpdateLinks.map(pl => (pl.roadNumber, pl.roadPartNumber)).toSet)
        None
      }
    } catch {
      case ex: RoadAddressException =>
        logger.info("Road address Exception: " + ex.getMessage)
        Some(s"Tieosoitevirhe: (${ex.getMessage}")
      case ex: ProjectValidationException => Some(ex.getMessage)
    }
  }

  private def recalculateProjectLinks(projectId: Long, userName: String, roadParts: Set[(Long, Long)] = Set()) = {

    def setReversedFlag(adjustedLink: ProjectLink, before: Option[ProjectLink]): ProjectLink = {
      before.map(_.sideCode) match {
        case Some(value) if value != adjustedLink.sideCode && value != SideCode.Unknown =>
          adjustedLink.copy(reversed = !adjustedLink.reversed)
        case _ => adjustedLink
      }
    }

    val projectLinks =
      if (roadParts.isEmpty)
        ProjectDAO.getProjectLinks(projectId)
      else
        ProjectDAO.fetchByProjectRoadParts(roadParts, projectId)
    logger.info(s"Recalculating project $projectId, parts ${roadParts.map(p => s"${p._1}/${p._2}").mkString(", ")}")

    withTiming(projectLinks.groupBy(
      pl => (pl.roadNumber, pl.roadPartNumber)).foreach {
      grp =>
        val calibrationPoints = CalibrationPointDAO.fetchByRoadPart(projectId, grp._1._1, grp._1._2)
        val recalculatedProjectLinks = ProjectSectionCalculator.assignMValues(grp._2, calibrationPoints).map( rpl =>
          setReversedFlag(rpl, grp._2.find(pl => pl.id == rpl.id && rpl.roadAddressId != 0L))
        )
        ProjectDAO.updateProjectLinksToDB(recalculatedProjectLinks, userName)
    }, "recalculated links in %.3f sec")
  }

  private def recalculateChangeTable(projectId: Long): Boolean = {
    val projectOpt = ProjectDAO.getRoadAddressProjectById(projectId)
    if (projectOpt.isEmpty)
      throw new IllegalArgumentException("Project not found")
    val project = projectOpt.get
    project.status match {
      case ProjectState.Saved2TR => true
      case _ =>
        val delta = ProjectDeltaCalculator.delta(project)
        setProjectDeltaToDB(delta, projectId)

    }
  }

  /**
    * method to check if project is publishable. add filters for cases we do not want to prevent sending
    * @param projectId project-id
    * @return if project contains any notifications preventing sending
    */
  def isProjectPublishable(projectId: Long): Boolean = {
    validateProjectById(projectId).isEmpty
  }

  def allLinksHandled(projectId: Long): Boolean ={ //some tests want to know if all projectlinks have been handled. to remove this test need to be updated to check if termination is correctly applied etc best done after all validations have been implemented
    withDynSession {
      ProjectDAO.getProjectLinks(projectId, Some(LinkStatus.NotHandled)).isEmpty &&
        ProjectDAO.getProjectLinks(projectId).nonEmpty
    }
  }

  /** Nullifies projects tr_id attribute, changes status to unfinnished and saves tr_info value to status_info. Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars
    *
    * @param projectId project-id
    * @return returns option error string
    */
  def removeRotatingTRId(projectId: Long): Option[String] = {
    withDynSession {
      val projects = ProjectDAO.getRoadAddressProjects(projectId)
      val rotatingTR_Id = ProjectDAO.getRotatingTRProjectId(projectId)
      ProjectDAO.updateProjectStatus(projectId, ProjectState.Incomplete)
      val addedStatus = if (rotatingTR_Id.isEmpty) "" else "[OLD TR_ID was " + rotatingTR_Id.head + "]"
      if (projects.isEmpty)
        return Some("Projectia ei löytynyt")
      val project = projects.head
      appendStatusInfo(project, addedStatus)
    }
    None
  }

  /**
    * Tries to append old status info if it is possible
    * otherwise it only takes first 300 chars of the old status
    *
    * @param project
    * @param appendMessage
    */
  private def appendStatusInfo(project: RoadAddressProject, appendMessage: String) = {
    val maxStringLenght = 1000
    project.statusInfo match { // before removing tr-id we want to save it in statusinfo if we need it later. Currently it is overwriten when we resend and get new error
      case Some(statusInfo) =>
        if ((statusInfo + appendMessage).length < maxStringLenght)
          ProjectDAO.updateProjectStateInfo(appendMessage + statusInfo, project.id)
        else if (statusInfo.length + appendMessage.length < 600)
          ProjectDAO.updateProjectStateInfo(appendMessage + statusInfo.substring(0, 300), project.id)
      case None =>
        if (appendMessage.nonEmpty)
          ProjectDAO.updateProjectStateInfo(appendMessage, project.id)
    }
    ProjectDAO.removeRotatingTRProjectId(project.id)


  }

  /**
    * Publish project with id projectId
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    // TODO: Check that project actually is finished: projectLinkPublishable(projectId)
    // TODO: Run post-change tests for the roads that have been edited and throw an exception to roll back if not acceptable
    withDynTransaction {
      try {
        if(!recalculateChangeTable(projectId)) {
          return PublishResult(false, false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))}
        ProjectDAO.addRotatingTRProjectId(projectId) //Generate new TR_ID
        val trProjectStateMessage = getRoadAddressChangesAndSendToTR(Set(projectId))
        if (trProjectStateMessage.status == ProjectState.Failed2GenerateTRIdInViite.value) {
          return PublishResult(false, false, Some(trProjectStateMessage.reason))
        }
        trProjectStateMessage.status match {
          case it if 200 until 300 contains it => {
            setProjectStatusToSend2TR(projectId)
            PublishResult(true, true, Some(trProjectStateMessage.reason))
          }
          case _ => {
            //rollback
            PublishResult(true, false, Some(trProjectStateMessage.reason))
          }
        }
      } catch {
        case NonFatal(e) => PublishResult(false, false, None)
      }
    }
  }

  private def setProjectDeltaToDB(projectDelta: Delta, projectId: Long): Boolean = {
    RoadAddressChangesDAO.clearRoadChangeTable(projectId)
    RoadAddressChangesDAO.insertDeltaToRoadChangeTable(projectDelta, projectId)
  }

  private def newProjectTemplate(rl: RoadLinkLike, ra: RoadAddress, project: RoadAddressProject): ProjectLink = {
    val geometry = GeometryUtils.truncateGeometry3D(rl.geometry, ra.startMValue, ra.endMValue)
    ProjectLink(NewRoadAddress, ra.roadNumber, ra.roadPartNumber, ra.track, ra.discontinuity, ra.startAddrMValue,
      ra.endAddrMValue, ra.startDate, ra.endDate, Some(project.modifiedBy), 0L, ra.linkId, ra.startMValue, ra.endMValue,
      ra.sideCode, ra.calibrationPoints, ra.floating, geometry,
      project.id, LinkStatus.NotHandled, ra.roadType, ra.linkGeomSource, GeometryUtils.geometryLength(geometry),
      ra.id, ra.ely, false, None, ra.adjustedTimestamp)
  }

  private def newProjectLink(rl: RoadLinkLike, project: RoadAddressProject, roadNumber: Long,
                             roadPartNumber: Long, trackCode: Track, discontinuity: Discontinuity, roadType: RoadType,
                             ely: Long): ProjectLink = {
    ProjectLink(NewRoadAddress, roadNumber, roadPartNumber, trackCode, discontinuity,
      0L, 0L, Some(project.startDate), None, Some(project.modifiedBy), 0L, rl.linkId, 0.0, rl.length,
      SideCode.Unknown, (None, None), false, rl.geometry,
      project.id, LinkStatus.New, roadType, rl.linkSource, rl.length,
      0L, ely, false, None, rl.vvhTimeStamp)
  }
  private def newProjectLink(rl: RoadLinkLike, project: RoadAddressProject, splitOptions: SplitOptions): ProjectLink = {
    newProjectLink(rl, project, splitOptions.roadNumber, splitOptions.roadPartNumber, splitOptions.trackCode,
      splitOptions.discontinuity, splitOptions.roadType, splitOptions.ely)
  }

  private def buildProjectRoadLink(projectLinks: Seq[ProjectLink]): Seq[ProjectAddressLink] = {
    val pl: Seq[ProjectLink] = projectLinks.size match {
      case 0 => return Seq()
      case 1 => projectLinks
      case _ => fuseProjectLinks(projectLinks)
    }
    pl.map(l => ProjectAddressLinkBuilder.build(l))
  }

  private def fuseProjectLinks(links: Seq[ProjectLink]) = {
    val linkIds = links.map(_.linkId).distinct
    if (linkIds.size != 1)
      throw new IllegalArgumentException(s"Multiple road link ids given for building one link: ${linkIds.mkString(", ")}")
    if (links.exists(_.isSplit))
      links
    else {
      val geom = links.head.sideCode match {
        case SideCode.TowardsDigitizing => links.map(_.geometry).foldLeft(Seq[Point]())((geometries, ge) => geometries ++ ge)
        case _ => links.map(_.geometry).reverse.foldLeft(Seq[Point]())((geometries, ge) => geometries ++ ge)
      }
      val (startM, endM, startA, endA) = (links.map(_.startMValue).min, links.map(_.endMValue).max,
        links.map(_.startAddrMValue).min, links.map(_.endAddrMValue).max)
      Seq(links.head.copy(startMValue = startM, endMValue = endM, startAddrMValue = startA, endAddrMValue = endA, geometry = geom))
    }
  }

  private def awaitRoadLinks(fetch: (Future[Seq[RoadLink]], Future[Seq[RoadLink]], Future[Seq[VVHRoadlink]])) = {
    val combinedFuture = for {
      fStandard <- fetch._1
      fComplementary <- fetch._2
      fSuravage <- fetch._3
    } yield (fStandard, fComplementary, fSuravage)

    val (roadLinks, complementaryLinks, suravageLinks) = Await.result(combinedFuture, Duration.Inf)
    (roadLinks, complementaryLinks, suravageLinks)
  }

  def getProjectStatusFromTR(projectId: Long) = {
    ViiteTierekisteriClient.getProjectStatus(projectId)
  }

  private def getStatusFromTRObject(trProject: Option[TRProjectStatus]): Option[ProjectState] = {
    trProject match {
      case Some(trProjectobject) => mapTRStateToViiteState(trProjectobject.status.getOrElse(""))
      case None => None
      case _ => None
    }
  }

  private def getTRErrorMessage(trProject: Option[TRProjectStatus]): String = {
    trProject match {
      case Some(trProjectobject) => trProjectobject.errorMessage.getOrElse("")
      case None => ""
      case _ => ""
    }
  }

  def setProjectStatusToSend2TR(projectId: Long): Unit = {
    ProjectDAO.updateProjectStatus(projectId, ProjectState.Sent2TR)
  }

  def updateProjectStatusIfNeeded(currentStatus: ProjectState, newStatus: ProjectState, errorMessage: String, projectId: Long): (ProjectState) = {
    if (currentStatus.value != newStatus.value && newStatus != ProjectState.Unknown) {
      val projects = ProjectDAO.getRoadAddressProjects(projectId)
      if (projects.nonEmpty && newStatus == ProjectState.ErroredInTR) // We write error message and clear old TR_ID which was stored there, so user wont see it in hower
        ProjectDAO.updateProjectStateInfo(errorMessage, projectId)
      ProjectDAO.updateProjectStatus(projectId, newStatus)
    }
    if (newStatus != ProjectState.Unknown) {
      newStatus
    } else {
      currentStatus
    }
  }

  private def getProjectsPendingInTR: Seq[Long] = {
    withDynSession {
      ProjectDAO.getProjectsWithWaitingTRStatus()
    }
  }

  def updateProjectsWaitingResponseFromTR(): Unit = {
    val listOfPendingProjects = getProjectsPendingInTR
    for (project <- listOfPendingProjects) {
      try {
        withDynTransaction {
          logger.info(s"Checking status for $project")
          checkAndUpdateProjectStatus(project)
        } match {
          case true => eventbus.publish("roadAddress:RoadNetworkChecker", RoadCheckOptions(Seq()))
          case _ => logger.info(s"Not going to check road network (status != Saved2TR)")
        }
      } catch {
        case t: Exception => logger.warn(s"Couldn't update project $project", t.getMessage)
      }
    }

  }

  private def checkAndUpdateProjectStatus(projectID: Long): Boolean = {
    ProjectDAO.getRotatingTRProjectId(projectID).headOption match {
      case Some(trId) =>
        ProjectDAO.getProjectStatus(projectID).map { currentState =>
          logger.info(s"Current status is $currentState")
          val trProjectState = ViiteTierekisteriClient.getProjectStatusObject(trId)
          val newState = getStatusFromTRObject(trProjectState).getOrElse(ProjectState.Unknown)
          val errorMessage = getTRErrorMessage(trProjectState)
          logger.info(s"TR returned project status for $projectID: $currentState -> $newState, errMsg: $errorMessage")
          val updatedStatus = updateProjectStatusIfNeeded(currentState, newState, errorMessage, projectID)
          if (updatedStatus == Saved2TR)
            updateRoadAddressWithProjectLinks(updatedStatus, projectID)
        }
        RoadAddressDAO.fetchAllFloatingRoadAddresses(false).isEmpty
      case None =>
        logger.info(s"During status checking VIITE wasnt able to find TR_ID to project $projectID")
        appendStatusInfo(ProjectDAO.getRoadAddressProjectById(projectID).head, " Failed to find TR-ID ")
        false
    }
  }

  private def mapTRStateToViiteState(trState: String): Option[ProjectState] = {

    trState match {
      case "S" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "K" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "T" => Some(ProjectState.apply(ProjectState.Saved2TR.value))
      case "V" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case "null" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case _ => None
    }
  }

  def createSplitRoadAddress(roadAddress: RoadAddress, split: Seq[ProjectLink], project: RoadAddressProject): Seq[RoadAddress] = {
    def transferValues(terminated: Option[ProjectLink]): (Long, Long, Double, Double) = {
      terminated.map(termLink =>
        termLink.sideCode match {
          case AgainstDigitizing =>
            if (termLink.startAddrMValue == roadAddress.startAddrMValue)
              (termLink.endAddrMValue, roadAddress.endAddrMValue,
                roadAddress.startMValue, termLink.startMValue)
            else (roadAddress.startAddrMValue, termLink.startAddrMValue,
              termLink.endMValue, roadAddress.endMValue)
          case _ =>
            if (termLink.startAddrMValue == roadAddress.startAddrMValue)
              (termLink.endAddrMValue, roadAddress.endAddrMValue,
                termLink.endMValue, roadAddress.endMValue)
            else (roadAddress.startAddrMValue, termLink.startAddrMValue,
              roadAddress.startMValue, termLink.startMValue)
        }
      ).getOrElse(roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startMValue, roadAddress.endMValue)
    }
    split.flatMap(pl =>
      pl.status match {
        case UnChanged =>
          Seq(roadAddress.copy(id = NewRoadAddress, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
            modifiedBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue, endMValue = pl.endMValue,
            adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry))
        case New =>
          Seq(RoadAddress(NewRoadAddress, pl.roadNumber, pl.roadPartNumber, pl.roadType, pl.track, pl.discontinuity,
            pl.startAddrMValue, pl.endAddrMValue, Some(project.startDate), None, Some(project.createdBy), 0L, pl.linkId,
            pl.startMValue, pl.endMValue, pl.sideCode, pl.linkGeometryTimeStamp, pl.calibrationPoints, floating = false,
            pl.geometry, pl.linkGeomSource, pl.ely, terminated = NoTermination, NewCommonHistoryId))
        case Transfer => // TODO if the whole common history -segment is transferred, keep the original common_history_id, otherwise generate new ids for the different segments
          val (startAddr, endAddr, startM, endM) = transferValues(split.find(_.status == Terminated))
          Seq(
            //TODO we should check situations where we need to create one new commonHistory for new and transfer/unchanged
            // Transferred part, original values
            roadAddress.copy(id = NewRoadAddress, startAddrMValue = startAddr, endAddrMValue = endAddr,
              endDate = Some(project.startDate), modifiedBy = Some(project.createdBy), startMValue = startM, endMValue = endM),
            // Transferred part, new values
            roadAddress.copy(id = NewRoadAddress, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
              startDate = Some(project.startDate), modifiedBy = Some(project.createdBy), linkId = pl.linkId,
              startMValue = pl.startMValue, endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp,
              geometry = pl.geometry)
          )
        case Terminated => // TODO Check common_history_id
          Seq(roadAddress.copy(id = NewRoadAddress, startAddrMValue = pl.startAddrMValue, endAddrMValue = pl.endAddrMValue,
            endDate = Some(project.startDate), modifiedBy = Some(project.createdBy), linkId = pl.linkId, startMValue = pl.startMValue,
            endMValue = pl.endMValue, adjustedTimestamp = pl.linkGeometryTimeStamp, geometry = pl.geometry, terminated = Termination))
        case _ =>
          logger.error(s"Invalid status for split project link: ${pl.status} in project ${pl.projectId}")
          throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
      }
    )
  }

  def updateTerminationForHistory(terminatedLinkIds: Set[Long], splitReplacements: Seq[ProjectLink]): Unit = {
    RoadAddressDAO.setSubsequentTermination(terminatedLinkIds)
    val mapping = RoadAddressSplitMapper.createAddressMap(splitReplacements)
    val splitTerminationLinkIds = mapping.map(_.sourceLinkId).toSet
    val splitCurrentRoadAddressIds = splitReplacements.map(_.roadAddressId).toSet
    val linkGeomSources = splitReplacements.map(pl => pl.linkId -> pl.linkGeomSource).toMap
    val addresses = RoadAddressDAO.fetchByLinkId(splitTerminationLinkIds, includeFloating = true, includeHistory = true,
      includeTerminated = false, splitCurrentRoadAddressIds) // Do not include current ones as they're created separately with other project links
    val splitAddresses = addresses.flatMap(RoadAddressSplitMapper.mapRoadAddresses(mapping)).map(ra =>
      ra.copy(terminated = if (splitTerminationLinkIds.contains(ra.linkId)) Subsequent else NoTermination,
        linkGeomSource = linkGeomSources(ra.linkId)))
    roadAddressService.expireRoadAddresses(addresses.map(_.id).toSet)
    RoadAddressDAO.create(splitAddresses)
  }

  def updateRoadAddressWithProjectLinks(newState: ProjectState, projectID: Long): Option[String] = {
    if (newState != Saved2TR) {
      throw new RuntimeException(s"Project state not at Saved2TR: $newState")
    }
    val project=ProjectDAO.getRoadAddressProjectById(projectID).get
    val projectLinks=ProjectDAO.getProjectLinks(projectID)
    if (projectLinks.isEmpty)
      throw new RuntimeException(s"Tried to import empty project to road address table after TR response : $newState")

    val (replacements, additions) = projectLinks.partition(_.roadAddressId > 0)
    val expiringRoadAddresses = RoadAddressDAO.queryById(replacements.map(_.roadAddressId).toSet).map(ra => ra.id -> ra).toMap
    logger.info(s"Found ${expiringRoadAddresses.size} to expire; expected ${replacements.map(_.roadAddressId).toSet.size}")
    if(expiringRoadAddresses.size != replacements.map(_.roadAddressId).toSet.size){
      throw new InvalidAddressDataException(s"The number of road_addresses to expire does not match the project_links to insert")
    }
    ProjectDAO.moveProjectLinksToHistory(projectID)

    try {
      val (splitReplacements, pureReplacements) = replacements.partition(_.connectedLinkId.nonEmpty)
      val (roadAddressesWithoutGeom, newRoadAddresses) = convertToRoadAddress(splitReplacements, pureReplacements, additions,
        expiringRoadAddresses, project).partition(_.floating)

      //Expiring all old addresses by their ID
      roadAddressService.expireRoadAddresses(expiringRoadAddresses.keys.toSet)
      val terminatedLinkIds = pureReplacements.filter(pl => pl.status == Terminated).map(_.linkId).toSet
      updateTerminationForHistory(terminatedLinkIds, splitReplacements)
      //Create endDate rows for old data that is "valid" (row should be ignored after end_date)
      val created = RoadAddressDAO.create(guessGeom.guestimateGeometry(roadAddressesWithoutGeom, newRoadAddresses))
      Some(s"${created.size} road addresses created")
    } catch {
      case e: ProjectValidationException => {
        logger.info(e.getMessage)
        Some(e.getMessage)
      }
    }
  }

  def convertToRoadAddress(splitReplacements: Seq[ProjectLink], pureReplacements: Seq[ProjectLink], additions: Seq[ProjectLink],
                           roadAddresses: Map[Long, RoadAddress], project: RoadAddressProject): Seq[RoadAddress] = {
    splitReplacements.groupBy(_.roadAddressId).flatMap { case (id, seq) =>
      createSplitRoadAddress(roadAddresses(id), seq, project)
    }.toSeq ++
      pureReplacements.map(pl => convertProjectLinkToRoadAddress(pl, project, roadAddresses.get(pl.roadAddressId))) ++
      additions.map(pl => convertProjectLinkToRoadAddress(pl, project, roadAddresses.get(pl.roadAddressId))) ++
      pureReplacements.flatMap(pl =>
        setEndDate(roadAddresses(pl.roadAddressId), pl, None))
  }

  private def convertProjectLinkToRoadAddress(pl: ProjectLink, project: RoadAddressProject,
                                              source: Option[RoadAddress]): RoadAddress = {
    val geom = if (pl.geometry.nonEmpty) {
      val linkGeom = GeometryUtils.geometryEndpoints(GeometryUtils.truncateGeometry2D(pl.geometry, pl.startMValue, pl.endMValue))
      if (pl.sideCode == SideCode.TowardsDigitizing)
        Seq(linkGeom._1, linkGeom._2)
      else
        Seq(linkGeom._2, linkGeom._1)
    } else {
      Seq()
    }
    val roadAddress = RoadAddress(NewRoadAddress, pl.roadNumber, pl.roadPartNumber, pl.roadType, pl.track, pl.discontinuity,
      pl.startAddrMValue, pl.endAddrMValue, None, None, pl.modifiedBy, 0L, pl.linkId, pl.startMValue, pl.endMValue, pl.sideCode,
      pl.linkGeometryTimeStamp, pl.calibrationPoints, floating = false, geom, pl.linkGeomSource, pl.ely, terminated = NoTermination, source.get.commonHistoryId)
    pl.status match {
      case UnChanged =>
        roadAddress.copy(startDate = source.get.startDate, endDate = source.get.endDate)
      //TODO in this situations we should check whether we should use one new CommonHistoryId or not
      case Transfer | Numbering =>
        roadAddress.copy(startDate = Some(project.startDate))
      //TODO in new situations we should probably always user NewCommonId
      case New =>
        roadAddress.copy(startDate = Some(project.startDate))
      case Terminated =>
        roadAddress.copy(startDate = source.get.startDate, endDate = Some(project.startDate), terminated = Termination)
      case _ =>
        logger.error(s"Invalid status for imported project link: ${pl.status} in project ${pl.projectId}")
        throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
    }
  }

  /**
    * Called for road addresses that are replaced by a new version at end date
    *
    * @param roadAddress
    * @param pl
    * @param vvhLink
    * @return
    */
  private def setEndDate(roadAddress: RoadAddress, pl: ProjectLink, vvhLink: Option[VVHRoadlink]): Option[RoadAddress] = {
    pl.status match {
      // Unchanged does not get an end date, terminated is created from the project link in convertProjectLinkToRoadAddress
      case UnChanged | Terminated =>
        None
      case Transfer | Numbering => // TODO Check common_history_id
        Some(roadAddress.copy(id = NewRoadAddress, endDate = pl.startDate))
      case _ =>
        logger.error(s"Invalid status for imported project link: ${pl.status} in project ${pl.projectId}")
        throw new InvalidAddressDataException(s"Invalid status for split project link: ${pl.status}")
    }
  }

  def setProjectEly(currentProjectId: Long, newEly: Long): Option[String] = {
    withDynTransaction {
      getProjectEly(currentProjectId).filterNot(_ == newEly).map { currentProjectEly =>
        logger.info(s"The project can not handle multiple ELY areas (the project ELY range is $currentProjectEly). Recording was discarded.")
        s"Projektissa ei voi käsitellä useita ELY-alueita (projektin ELY-alue on $currentProjectEly). Tallennus hylättiin."
      }.orElse {
        ProjectDAO.updateProjectEly(currentProjectId, newEly)
        None
      }
    }
  }

  def correctNullProjectEly(): Unit = {
    withDynSession {
      //Get all the projects with non-existant ely code
      val nullElyProjects = ProjectDAO.getRoadAddressProjects(0, true)
      nullElyProjects.foreach(project => {
        //Get all the reserved road parts of said projects
        val reservedRoadParts = ProjectDAO.fetchReservedRoadParts(project.id).filterNot(_.ely == 0)
        //Find the lowest maddressValue of the reserved road parts
        val reservedRoadAddresses = RoadAddressDAO.fetchByRoadPart(reservedRoadParts.head.roadNumber, reservedRoadParts.head.roadPartNumber).minBy(_.endAddrMValue)
        //Use this part ELY code and set it on the project
        ProjectDAO.updateProjectEly(project.id, reservedRoadAddresses.ely)
      })
    }
  }

  def getProjectEly(projectId: Long): Option[Long] = {
    ProjectDAO.getProjectEly(projectId)
  }

  def validateProjectById(projectId: Long): Seq[ValidationErrorDetails] = {
    withDynSession {
      ProjectValidator.validateProject(ProjectDAO.getRoadAddressProjectById(projectId).get, ProjectDAO.getProjectLinks(projectId))
    }
  }

  def validateLinkTrack(track: Int): Boolean = {
    Track.values.filterNot(_.value == Track.Unknown.value).exists(_.value == track)
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])

}

class SplittingException(s: String) extends RuntimeException {
  override def getMessage: String = s
}

case class ProjectBoundingBoxResult(projectLinkResultF: Future[Map[Long, Seq[ProjectLink]]], roadLinkF: Future[Seq[RoadLink]],
                                    complementaryF: Future[Seq[RoadLink]], suravageF: Future[Seq[VVHRoadlink]])
