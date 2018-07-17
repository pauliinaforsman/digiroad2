package fi.liikennevirasto.digiroad2.service.linearasset

import java.util.Properties

import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType, VVHClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.{InaccurateAssetDAO, OracleAssetDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.OracleSpeedLimitDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.{ChangeSet, MValueAdjustment, SideCodeAdjustment, VVHChangesAdjustment}
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.process.SpeedLimitValidator
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.pointasset.TrafficSignService
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, PolygonTools}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

case class ChangedSpeedLimit(speedLimit: SpeedLimit, link: RoadLink)

class SpeedLimitService(eventbus: DigiroadEventBus, vvhClient: VVHClient, roadLinkService: RoadLinkService) {
  val dao: OracleSpeedLimitDao = new OracleSpeedLimitDao(vvhClient, roadLinkService)
  val inaccurateAssetDao: InaccurateAssetDAO = new InaccurateAssetDAO()
  val assetDao: OracleAssetDao = new OracleAssetDao()
  val logger = LoggerFactory.getLogger(getClass)
  val polygonTools: PolygonTools = new PolygonTools
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  lazy val dr2properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

  lazy val userProvider: UserProvider = {
    Class.forName(dr2properties.getProperty("digiroad2.userProvider")).newInstance().asInstanceOf[UserProvider]
  }

  lazy val trafficSignService: TrafficSignService = {
    new TrafficSignService(roadLinkService, userProvider)
  }

  lazy val speedLimitValidator: SpeedLimitValidator = {
    new SpeedLimitValidator(trafficSignService)
  }

  def validateMunicipalities(id: Long,  municipalityValidation: (Int, AdministrativeClass) => Unit, newTransaction: Boolean = true): Unit = {
    getLinksWithLengthFromVVH(id, newTransaction).foreach(vvhLink => municipalityValidation(vvhLink._4, vvhLink._6))
  }

  def getLinksWithLengthFromVVH(id: Long, newTransaction: Boolean = true): Seq[(Long, Double, Seq[Point], Int, LinkGeomSource, AdministrativeClass)] = {
    if (newTransaction)
      withDynTransaction {
        dao.getLinksWithLengthFromVVH(id)
      }
    else
      dao.getLinksWithLengthFromVVH(id)
  }

  //-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------//

  def getSpeedLimitAssetsByIds(ids: Set[Long], newTransaction: Boolean = true): Seq[SpeedLimit] = {
    if (newTransaction)
      withDynTransaction {
        dao.getSpeedLimitLinksByIds(ids)
      }
    else
      dao.getSpeedLimitLinksByIds(ids)
  }

  def getSpeedLimitById(id: Long, newTransaction: Boolean = true): Option[SpeedLimit] = {
    getSpeedLimitAssetsByIds(Set(id), newTransaction).headOption
  }

  def getPersistedSpeedLimitByIds(ids: Set[Long], newTransaction: Boolean = true):  Seq[PersistedSpeedLimit] = {
    if (newTransaction)
      withDynTransaction {
        dao.getPersistedSpeedLimitByIds(ids)
      }
    else
      dao.getPersistedSpeedLimitByIds(ids)
  }

  def getPersistedSpeedLimitById(id: Long, newTransaction: Boolean = true): Option[PersistedSpeedLimit] = {
    getPersistedSpeedLimitByIds(Set(id), newTransaction).headOption
  }

  //-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------//

  def updateByExpiration(id: Long, expired: Boolean, username: String, newTransaction: Boolean = true):Option[Long] = {
    if (newTransaction)
      withDynTransaction {
        dao.updateExpiration(id, expired, username)
      }
    else
      dao.updateExpiration(id, expired, username)
  }

  def updateSideCode(id: Long, sideCode: SideCode, newTransaction: Boolean = true) ={
    if (newTransaction)
      withDynTransaction {
        dao.updateSideCode(id, sideCode)
      }
    else
      dao.updateSideCode(id, sideCode)
  }

  /**
    * Returns speed limits for Digiroad2Api /speedlimits GET endpoint.
    */
  def get(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[Seq[SpeedLimit]] = {
    val (roadLinks, change) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(bounds, municipalities)
    withDynTransaction {
      val (filledTopology,roadLinksByLinkId) = getByRoadLinks(roadLinks, change)
      LinearAssetPartitioner.partition(enrichSpeedLimitAttributes(filledTopology, roadLinksByLinkId), roadLinksByLinkId)
    }
  }

  /**
    * Returns speed limits by municipality. Used by IntegrationApi speed_limits endpoint.
    */
  def get(municipality: Int): Seq[SpeedLimit] = {
    val (roadLinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)
    withDynTransaction {
      getByRoadLinks(roadLinks, changes)._1
    }
  }

  /**
    * Returns speed limits history for Digiroad2Api /history speedlimits GET endpoint.
    */
  def getHistory(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[Seq[SpeedLimit]] = {
    val roadLinks = roadLinkService.getRoadLinksHistoryFromVVH(bounds, municipalities)
    withDynTransaction {
      val (filledTopology, roadLinksByLinkId) = getByRoadLinks(roadLinks, Seq(), true)
      LinearAssetPartitioner.partition(filledTopology, roadLinksByLinkId)
    }
  }

  /**
    * This method returns speed limits that have been changed in OTH between given date values. It is used by TN-ITS ChangeApi.
    *
    * @param sinceDate
    * @param untilDate
    * @return Changed speed limits
    */
  def getChanged(sinceDate: DateTime, untilDate: DateTime, withoutAdjust: Boolean = false): Seq[ChangedSpeedLimit] = {
    val persistedSpeedLimits = withDynTransaction {
      dao.getSpeedLimitsChangedSince(sinceDate, untilDate, withoutAdjust)
    }
    val roadLinks = roadLinkService.getRoadLinksAndComplementariesFromVVH(persistedSpeedLimits.map(_.linkId).toSet)
    val roadLinksWithoutWalkways = roadLinks.filterNot(_.linkType == CycleOrPedestrianPath).filterNot(_.linkType == TractorRoad)

    persistedSpeedLimits.flatMap { speedLimit =>
      roadLinksWithoutWalkways.find(_.linkId == speedLimit.linkId).map { roadLink =>
        ChangedSpeedLimit(
          speedLimit = SpeedLimit(
            id = speedLimit.id,
            linkId = speedLimit.linkId,
            sideCode = speedLimit.sideCode,
            trafficDirection = roadLink.trafficDirection,
            value = speedLimit.value.map(NumericValue),
            geometry = GeometryUtils.truncateGeometry3D(roadLink.geometry, speedLimit.startMeasure, speedLimit.endMeasure),
            startMeasure = speedLimit.startMeasure,
            endMeasure = speedLimit.endMeasure,
            modifiedBy = speedLimit.modifiedBy, modifiedDateTime = speedLimit.modifiedDate,
            createdBy = speedLimit.createdBy, createdDateTime = speedLimit.createdDate,
            vvhTimeStamp = speedLimit.vvhTimeStamp, geomModifiedDate = speedLimit.geomModifiedDate,
            expired = speedLimit.expired,
            linkSource = roadLink.linkSource
          ),
          link = roadLink
        )
      }
    }
  }

  /**
    * Returns unknown speed limits for Digiroad2Api /speedlimits/unknown GET endpoint.
    */
  def getUnknown(municipalities: Set[Int], administrativeClass: Option[AdministrativeClass]): Map[String, Map[String, Any]] = {
    withDynSession {
      dao.getUnknownSpeedLimits(municipalities, administrativeClass)
    }
  }

  def getMunicipalitiesWithUnknown(administrativeClass: Option[AdministrativeClass]): Seq[(Long, String)] = {
    withDynSession {
      dao.getMunicipalitiesWithUnknown(administrativeClass)
    }
  }

  /**
    * Removes speed limit from unknown speed limits list if speed limit exists. Used by SpeedLimitUpdater actor.
    */
  def purgeUnknown(linkIds: Set[Long]): Unit = {
    val roadLinks = vvhClient.roadLinkData.fetchByLinkIds(linkIds)
    withDynTransaction {
      roadLinks.foreach { rl =>
        dao.purgeFromUnknownSpeedLimits(rl.linkId, GeometryUtils.geometryLength(rl.geometry))
      }
    }
  }

  private def createUnknownLimits(speedLimits: Seq[SpeedLimit], roadLinksByLinkId: Map[Long, RoadLink]): Seq[UnknownSpeedLimit] = {
    val generatedLimits = speedLimits.filter(speedLimit => speedLimit.id == 0 && speedLimit.value.isEmpty)
    generatedLimits.map { limit =>
      val roadLink = roadLinksByLinkId(limit.linkId)
      UnknownSpeedLimit(roadLink.linkId, roadLink.municipalityCode, roadLink.administrativeClass)
    }
  }

  private def getByRoadLinks(roadLinks: Seq[RoadLink], change: Seq[ChangeInfo], showSpeedLimitsHistory: Boolean = false) = {

    val (speedLimitLinks, topology) = dao.getSpeedLimitLinksByRoadLinks(roadLinks, showSpeedLimitsHistory)
    val mappedChanges = LinearAssetUtils.getMappedChanges(change)
    val oldRoadLinkIds = LinearAssetUtils.deletedRoadLinkIds(mappedChanges, roadLinks.map(_.linkId).toSet)
    val oldSpeedLimits = dao.getCurrentSpeedLimitsByLinkIds(Some(oldRoadLinkIds.toSet))

    // filter those road links that have already been projected earlier from being reprojected
    val speedLimitsOnChangedLinks = speedLimitLinks.filter(sl => LinearAssetUtils.newChangeInfoDetected(sl, mappedChanges))

    val projectableTargetRoadLinks = roadLinks.filter(rl => rl.linkType.value == UnknownLinkType.value || rl.isCarTrafficRoad)


    val initChangeSet = ChangeSet(droppedAssetIds = Set.empty[Long],
                                  expiredAssetIds = oldSpeedLimits.map(_.id).toSet,
                                  adjustedMValues = Seq.empty[MValueAdjustment],
                                  adjustedVVHChanges = Seq.empty[VVHChangesAdjustment],
                                  adjustedSideCodes = Seq.empty[SideCodeAdjustment])


    val (newSpeedLimits, projectedChangeSet) = fillNewRoadLinksWithPreviousSpeedLimitData(projectableTargetRoadLinks, oldSpeedLimits ++ speedLimitsOnChangedLinks,
                                                                    speedLimitsOnChangedLinks, change, initChangeSet)

    val speedLimits = (speedLimitLinks ++ newSpeedLimits).groupBy(_.linkId)
    val roadLinksByLinkId = topology.groupBy(_.linkId).mapValues(_.head)

    val (filledTopology, changeSet) = SpeedLimitFiller.fillTopology(topology, speedLimits, Some(projectedChangeSet))

    // Expire all assets that are dropped or expired. No more floating speed limits.
    eventbus.publish("linearAssets:update", changeSet.copy(expiredAssetIds = changeSet.expiredAssetIds ++ changeSet.droppedAssetIds, droppedAssetIds = Set()))
    eventbus.publish("speedLimits:saveProjectedSpeedLimits", filledTopology.filter(sl => sl.id <= 0 && sl.value.nonEmpty))

    eventbus.publish("speedLimits:purgeUnknownLimits", changeSet.adjustedMValues.map(_.linkId).toSet)
    val unknownLimits = createUnknownLimits(filledTopology, roadLinksByLinkId)
    eventbus.publish("speedLimits:persistUnknownLimits", unknownLimits)

    (filledTopology, roadLinksByLinkId)
  }

  /**
    * Uses VVH ChangeInfo API to map OTH speed limit information from old road links to new road links after geometry changes.
    */
  private def fillNewRoadLinksWithPreviousSpeedLimitData(roadLinks: Seq[RoadLink], speedLimitsToUpdate: Seq[SpeedLimit],
                                                         currentSpeedLimits: Seq[SpeedLimit], changes: Seq[ChangeInfo], changeSet: ChangeSet) : (Seq[SpeedLimit], ChangeSet) ={


    mapReplacementProjections(speedLimitsToUpdate, currentSpeedLimits, roadLinks, changes).foldLeft((Seq.empty[SpeedLimit], changeSet)) {
      case ((persistedSpeed, cs), (asset, (Some(roadLink), Some(projection)))) =>
        val (speedLimit, changes) = SpeedLimitFiller.projectSpeedLimit(asset, roadLink, projection, cs)
        ((persistedSpeed ++ Seq(speedLimit)).filter(sl => Math.abs(sl.startMeasure - sl.endMeasure) > 0) , changes)

      case _=> (Seq.empty[SpeedLimit], changeSet)
    }
  }

  private def mapReplacementProjections(oldSpeedLimits: Seq[SpeedLimit], currentSpeedLimits: Seq[SpeedLimit], roadLinks: Seq[RoadLink],
                                changes: Seq[ChangeInfo]) : Seq[(SpeedLimit, (Option[RoadLink], Option[Projection]))] = {
    val targetLinks = changes.flatMap(_.newId).toSet
    val newRoadLinks = roadLinks.filter(rl => targetLinks.contains(rl.linkId)).groupBy(_.linkId)
    val changeMap = changes.filterNot(c => c.newId.isEmpty || c.oldId.isEmpty).map(c => (c.oldId.get, c.newId.get)).groupBy(_._1)
    val targetRoadLinks = changeMap.mapValues(a => a.flatMap(b => newRoadLinks.getOrElse(b._2, Seq())))
    oldSpeedLimits.flatMap{limit =>
      targetRoadLinks.getOrElse(limit.linkId, Seq()).map(newRoadLink =>
        (limit,
          getRoadLinkAndProjection(roadLinks, changes, limit.linkId, newRoadLink.linkId, oldSpeedLimits, currentSpeedLimits))
      )}
  }

  private def getRoadLinkAndProjection(roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo], oldId: Long, newId: Long,
                               speedLimitsToUpdate: Seq[SpeedLimit], currentSpeedLimits: Seq[SpeedLimit]) = {
    val roadLink = roadLinks.find(rl => newId == rl.linkId)
    val changeInfo = changes.find(c => c.oldId.getOrElse(0) == oldId && c.newId.getOrElse(0) == newId)
    val projection = changeInfo match {
      case Some(info) =>
        // ChangeInfo object related speed limits; either mentioned in oldId or in newId
        val speedLimits = speedLimitsToUpdate.filter(_.linkId == info.oldId.getOrElse(0L)) ++
          currentSpeedLimits.filter(_.linkId == info.newId.getOrElse(0L))
        mapChangeToProjection(info, speedLimits)
      case _ => None
    }
    (roadLink,projection)
  }

  private def mapChangeToProjection(change: ChangeInfo, speedLimits: Seq[SpeedLimit]) = {
    val typed = ChangeType.apply(change.changeType)
    typed match {
        // cases 5, 6, 1, 2
      case ChangeType.DividedModifiedPart  | ChangeType.DividedNewPart | ChangeType.CombinedModifiedPart |
           ChangeType.CombinedRemovedPart => projectSpeedLimitConditionally(change, speedLimits, testNoSpeedLimitExists)
        // cases 3, 7, 13, 14
      case ChangeType.LengthenedCommonPart | ChangeType.ShortenedCommonPart | ChangeType.ReplacedCommonPart |
           ChangeType.ReplacedNewPart =>
        projectSpeedLimitConditionally(change, speedLimits, testSpeedLimitOutdated)
      case _ => None
    }
  }

  private def testNoSpeedLimitExists(speedLimits: Seq[SpeedLimit], linkId: Long, mStart: Double, mEnd: Double, vvhTimeStamp: Long) = {
    !speedLimits.exists(l => l.linkId == linkId && GeometryUtils.overlaps((l.startMeasure,l.endMeasure),(mStart,mEnd)))
  }

  private def testSpeedLimitOutdated(speedLimits: Seq[SpeedLimit], linkId: Long, mStart: Double, mEnd: Double, vvhTimeStamp: Long) = {
    val targetLimits = speedLimits.filter(l => l.linkId == linkId)
    targetLimits.nonEmpty && !targetLimits.exists(l => l.vvhTimeStamp >= vvhTimeStamp)
  }

  private def projectSpeedLimitConditionally(change: ChangeInfo, limits: Seq[SpeedLimit], condition: (Seq[SpeedLimit], Long, Double, Double, Long) => Boolean) = {
    (change.newId, change.oldStartMeasure, change.oldEndMeasure, change.newStartMeasure, change.newEndMeasure, change.vvhTimeStamp) match {
      case (Some(newId), Some(oldStart:Double), Some(oldEnd:Double),
      Some(newStart:Double), Some(newEnd:Double), vvhTimeStamp) =>
        condition(limits, newId, newStart, newEnd, vvhTimeStamp) match {
          case true => Some(Projection(oldStart, oldEnd, newStart, newEnd, vvhTimeStamp))
          case false => None
        }
      case _ => None
    }
  }

  /**
    * Adds speed limits to unknown speed limits list. Used by SpeedLimitUpdater actor.
    * Links to unknown speed limits are shown on UI Worklist page.
    */
  def persistUnknown(limits: Seq[UnknownSpeedLimit]): Unit = {
    withDynTransaction {
      dao.persistUnknownSpeedLimits(limits)
    }
  }

  def persistProjectedLimit(limits: Seq[SpeedLimit]): Unit = {
    withDynTransaction {
      val (newlimits, changedlimits) = limits.partition(_.id <= 0)
      newlimits.foreach { limit =>
        dao.createSpeedLimit(limit.createdBy.getOrElse(LinearAssetTypes.VvhGenerated), limit.linkId, Measures(limit.startMeasure, limit.endMeasure),
          limit.sideCode, limit.value.get.value, Some(limit.vvhTimeStamp), limit.createdDateTime, limit.modifiedBy,
          limit.modifiedDateTime, limit.linkSource)
      }
    }
    // Add them to checks to remove unknown limits
    eventbus.publish("speedLimits:purgeUnknownLimits", limits.map(_.linkId).toSet)
  }

  /**
    * Saves speed limit value changes received from UI. Used by Digiroad2Api /speedlimits PUT endpoint.
    */
  def updateValues(ids: Seq[Long], value: Int, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[Long] = {
    withDynTransaction {
      ids.foreach( id => validateMunicipalities(id, municipalityValidation, newTransaction = false))
      ids.flatMap(dao.updateSpeedLimitValue(_, value, username))
    }
  }

  /**
    * Create new speed limit when value received from UI changes and expire the old one. Used by SpeeedLimitsService.updateValues.
    */

  def update(id: Long, newLimits: Seq[NewLinearAsset], username: String): Seq[Long] = {
    val oldSpeedLimit = getPersistedSpeedLimitById(id).map(toSpeedLimit).get

    newLimits.flatMap (limit =>  limit.value match {
      case NumericValue(intValue) =>

        if (((limit.startMeasure - oldSpeedLimit.startMeasure > 0.01  || oldSpeedLimit.startMeasure - limit.startMeasure > 0.01) || (limit.endMeasure - oldSpeedLimit.endMeasure > 0.01 || oldSpeedLimit.endMeasure - limit.endMeasure > 0.01)) || SideCode(limit.sideCode) != oldSpeedLimit.sideCode)
          updateSpeedLimitWithExpiration(id, intValue, username, Some(Measures(limit.startMeasure, limit.endMeasure)), Some(limit.sideCode), (_, _) => Unit)
        else
          updateValues(Seq(id), intValue, username, (_, _) => Unit)
      case _ => Seq.empty[Long]
    })
  }


  def updateSpeedLimitWithExpiration(id: Long, value: Int, username: String, measures: Option[Measures] = None, sideCode: Option[Int] = None, municipalityValidation: (Int, AdministrativeClass) => Unit): Option[Long] = {
    validateMunicipalities(id, municipalityValidation, newTransaction = false)

    //Get all data from the speedLimit to update
    val speedLimit = dao.getPersistedSpeedLimit(id).filterNot(_.expired).getOrElse(throw new IllegalStateException("Asset no longer available"))

    //Expire old speed limit
    dao.updateExpiration(id)

    //Create New Asset copy by the old one with new value
    val newAssetId =
      dao.createSpeedLimit(username, speedLimit.linkId, measures.getOrElse(Measures(speedLimit.startMeasure, speedLimit.endMeasure)),
        SideCode.apply(sideCode.getOrElse(speedLimit.sideCode.value)), value, None, None, None, None, speedLimit.linkSource)

    existOnInaccuratesList(id, newAssetId)
    newAssetId
  }

  private def existOnInaccuratesList(oldId: Long, newId: Option[Long] = None) = {
    (inaccurateAssetDao.getInaccurateAssetById(oldId), newId) match {
      case (Some(idOld), Some(idNew)) =>
        inaccurateAssetDao.deleteInaccurateAssetById(idOld)
        checkInaccurateSpeedLimit(newId)
      case _ => None
    }
  }

  private def checkInaccurateSpeedLimit(id: Option[Long] = None) = {
    dao.getSpeedLimitLinksById(id.head).foreach { speedLimit =>

      val roadLink = roadLinkService.getRoadLinkAndComplementaryFromVVH(speedLimit.linkId, false)
        .find(roadLink => roadLink.administrativeClass == State || roadLink.administrativeClass == Municipality)
        .getOrElse(throw new NoSuchElementException("Roadlink Not Found"))

      speedLimitValidator.checkInaccurateSpeedLimitValues(speedLimit, roadLink) match {
        case Some(speedLimitAsset) =>
          inaccurateAssetDao.createInaccurateAsset(speedLimitAsset.id, SpeedLimitAsset.typeId, roadLink.municipalityCode, roadLink.administrativeClass)
        case _ => None
      }
    }
  }

  /**
    * Saves speed limit values when speed limit is split to two parts in UI (scissors icon). Used by Digiroad2Api /speedlimits/:speedLimitId/split POST endpoint.
    */
  def split(id: Long, splitMeasure: Double, existingValue: Int, createdValue: Int, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[SpeedLimit] = {
    withDynTransaction {
      getPersistedSpeedLimitById(id, newTransaction = false) match {
        case Some(speedLimit) =>
          val roadLink = roadLinkService.fetchVVHRoadlinkAndComplementary(speedLimit.linkId).getOrElse(throw new IllegalStateException("Road link no longer available"))
          municipalityValidation(roadLink.municipalityCode, roadLink.administrativeClass)

          val (newId ,idUpdated) = splitSpeedLimit(speedLimit, roadLink, splitMeasure, existingValue, createdValue, username)

          val assets = getPersistedSpeedLimitByIds(Set(idUpdated, newId), newTransaction = false)

          val speedLimits = assets.map{ asset =>
            SpeedLimit(asset.id, asset.linkId, asset.sideCode, roadLink.trafficDirection, asset.value.map(NumericValue), GeometryUtils.truncateGeometry3D(roadLink.geometry, asset.startMeasure, asset.endMeasure),
              asset.startMeasure, asset.endMeasure, asset.modifiedBy, asset.modifiedDate, asset.createdBy, asset.createdDate, asset.vvhTimeStamp, asset.geomModifiedDate, linkSource = asset.linkSource)
          }
          speedLimits.filter(asset => asset.id == idUpdated || asset.id == newId)

        case _ => Seq()
      }
    }
  }

  /**
    * Splits speed limit by given split measure.
    * Used by SpeedLimitService.split.
    */
  def splitSpeedLimit(speedLimit: PersistedSpeedLimit, vvhRoadLink: VVHRoadlink, splitMeasure: Double, existingValue: Int, createdValue: Int, username: String): (Long, Long) = {
    val (existingLinkMeasures, createdLinkMeasures) = GeometryUtils.createSplit(splitMeasure, (speedLimit.startMeasure, speedLimit.endMeasure))

    updateByExpiration(speedLimit.id, expired = true, username)

    val existingId = dao.createSpeedLimit(username, speedLimit.linkId, Measures(existingLinkMeasures._1, existingLinkMeasures._2),
      speedLimit.sideCode, existingValue, Some(speedLimit.vvhTimeStamp), None, Some(username), Some(DateTime.now()) , vvhRoadLink.linkSource).get

    val createdId = dao.createSpeedLimit(username, vvhRoadLink.linkId, Measures(createdLinkMeasures._1, createdLinkMeasures._2),
      speedLimit.sideCode, createdValue, Option(speedLimit.vvhTimeStamp), None, Some(username), Some(DateTime.now()), vvhRoadLink.linkSource).get
    (existingId, createdId)
  }


  private def toSpeedLimit(persistedSpeedLimit: PersistedSpeedLimit): SpeedLimit = {
    val roadLink = roadLinkService.getRoadLinkAndComplementaryFromVVH(persistedSpeedLimit.linkId).get

    SpeedLimit(
      persistedSpeedLimit.id, persistedSpeedLimit.linkId, persistedSpeedLimit.sideCode,
      roadLink.trafficDirection, persistedSpeedLimit.value.map(NumericValue),
      GeometryUtils.truncateGeometry3D(roadLink.geometry, persistedSpeedLimit.startMeasure, persistedSpeedLimit.endMeasure),
      persistedSpeedLimit.startMeasure, persistedSpeedLimit.endMeasure,
      persistedSpeedLimit.modifiedBy, persistedSpeedLimit.modifiedDate,
      persistedSpeedLimit.createdBy, persistedSpeedLimit.createdDate, persistedSpeedLimit.vvhTimeStamp, persistedSpeedLimit.geomModifiedDate,
      linkSource = persistedSpeedLimit.linkSource)
  }

  private def isSeparableValidation(speedLimit: SpeedLimit): SpeedLimit = {
    val separable = speedLimit.sideCode == SideCode.BothDirections && speedLimit.trafficDirection == TrafficDirection.BothDirections
    if (!separable) throw new IllegalArgumentException
    speedLimit
  }

  /**
    * Saves speed limit values when speed limit is separated to two sides in UI. Used by Digiroad2Api /speedlimits/:speedLimitId/separate POST endpoint.
    */
  def separate(id: Long, valueTowardsDigitization: Int, valueAgainstDigitization: Int, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[SpeedLimit] = {
    val speedLimit = getPersistedSpeedLimitById(id)
      .map(toSpeedLimit)
      .map(isSeparableValidation)
      .get

    validateMunicipalities(id, municipalityValidation)

    updateByExpiration(id, expired = true, username)

    val(newId1, newId2) = withDynTransaction {
      (dao.createSpeedLimit(username, speedLimit.linkId, Measures(speedLimit.startMeasure, speedLimit.endMeasure), SideCode.TowardsDigitizing, valueTowardsDigitization, None, modifiedBy = Some(username), modifiedAt = Some(DateTime.now()), linkSource = speedLimit.linkSource).get,
       dao.createSpeedLimit(username, speedLimit.linkId, Measures(speedLimit.startMeasure, speedLimit.endMeasure), SideCode.AgainstDigitizing, valueAgainstDigitization, None, modifiedBy = Some(username), modifiedAt = Some(DateTime.now()),  linkSource = speedLimit.linkSource).get)
    }
    val assets = getSpeedLimitAssetsByIds(Set(newId1, newId2))
    Seq(assets.find(_.id == newId1).get, assets.find(_.id == newId2).get)
  }

  def getByMunicpalityAndRoadLinks(municipality: Int): Seq[(SpeedLimit, RoadLink)] = {
    val (roadLinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipality)
    val speedLimits = withDynTransaction {
      getByRoadLinks(roadLinks, changes)._1
    }
    speedLimits.map{ speedLimit => (speedLimit, roadLinks.find(_.linkId == speedLimit.linkId).getOrElse(throw new NoSuchElementException))}
  }

  /**
    * This method was created for municipalityAPI, in future could be merge with the other create method.
    */
  def createMultiple(newLimits: Seq[NewLinearAsset], typeId: Int, username: String, vvhTimeStamp: Long = vvhClient.roadLinkData.createVVHTimeStamp(),  municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[Long] = {
    withDynTransaction {
      val createdIds = newLimits.flatMap { limit =>
      limit.value match {
        case NumericValue(intValue) => dao.createSpeedLimit(username, limit.linkId, Measures(limit.startMeasure, limit.endMeasure), SideCode.apply(limit.sideCode), intValue, vvhTimeStamp, municipalityValidation)
        case _ => None
      }
    }
      eventbus.publish("speedLimits:purgeUnknownLimits", newLimits.map(_.linkId).toSet)
      createdIds
    }
  }

  /**
    * Saves new speed limit from UI. Used by Digiroad2Api /speedlimits PUT and /speedlimits POST endpoints.
    */
  def create(newLimits: Seq[NewLimit], value: Int, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[Long] = {
    withDynTransaction {
      val createdIds = newLimits.flatMap { limit =>
        dao.createSpeedLimit(username, limit.linkId, Measures(limit.startMeasure, limit.endMeasure), SideCode.BothDirections, value, vvhClient.roadLinkData.createVVHTimeStamp(), municipalityValidation)
      }
      eventbus.publish("speedLimits:purgeUnknownLimits", newLimits.map(_.linkId).toSet)
      createdIds
    }
  }

  private def addRoadAdministrationClassAttribute(speedLimit: SpeedLimit, roadLink: RoadLink): SpeedLimit = {
    speedLimit.copy(attributes = speedLimit.attributes ++ Map("ROAD_ADMIN_CLASS" -> roadLink.administrativeClass))
  }

  private def addMunicipalityCodeAttribute(speedLimit: SpeedLimit, roadLink: RoadLink): SpeedLimit = {
    speedLimit.copy(attributes = speedLimit.attributes ++ Map("municipalityCode" -> roadLink.municipalityCode))
  }

  private def enrichSpeedLimitAttributes(speedLimits: Seq[SpeedLimit], roadLinksForSpeedLimits: Map[Long, RoadLink]): Seq[SpeedLimit] = {
    val speedLimitAttributeOperations: Seq[(SpeedLimit, RoadLink) => SpeedLimit] = Seq(
      addRoadAdministrationClassAttribute,
      addMunicipalityCodeAttribute
      //In the future if we need to add more attributes just add a method here
    )

    speedLimits.map(speedLimit =>
      speedLimitAttributeOperations.foldLeft(speedLimit) { case (asset, operation) =>
        roadLinksForSpeedLimits.get(asset.linkId).map{
          roadLink =>
            operation(asset, roadLink)
        }.getOrElse(asset)
      }
    )
  }

  def getSpeedLimitsWithInaccurates(municipalities: Set[Int]= Set(), adminClassList: Set[AdministrativeClass] = Set()): Map[String, Map[String, Any]] = {

    case class WrongSpeedLimit(linkId: Long, municipality: String, administrativeClass: String)
    def toWrongSpeedLimit(x: (Long, String, Int)) = WrongSpeedLimit(x._1, x._2, AdministrativeClass(x._3).toString)

    withDynTransaction {
      val wrongSpeedLimits = inaccurateAssetDao.getInaccurateAssetByTypeId(SpeedLimitAsset.typeId, municipalities, adminClassList)
        .map(toWrongSpeedLimit)

            wrongSpeedLimits.groupBy(_.municipality)
        .mapValues {
          _.groupBy(_.administrativeClass)
            .mapValues(_.map(_.linkId))
        }
    }
  }
}
