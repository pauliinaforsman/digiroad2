package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.GeometryUtils._
import fi.liikennevirasto.digiroad2.asset.Asset._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkProperties}
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.user.User
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

case class IncompleteLink(linkId: Long, municipalityCode: Int, administrativeClass: AdministrativeClass)
case class RoadLinkChangeSet(adjustedRoadLinks: Seq[RoadLink], incompleteLinks: Seq[IncompleteLink])

class RoadLinkService(val vvhClient: VVHClient, val eventbus: DigiroadEventBus) {
  val logger = LoggerFactory.getLogger(getClass)

  def fetchVVHRoadlinks(linkIds: Set[Long]): Seq[VVHRoadlink] = {
    if (linkIds.nonEmpty) vvhClient.fetchVVHRoadlinks(linkIds)
    else Seq.empty[VVHRoadlink]
  }

  def fetchVVHRoadlinks[T](linkIds: Set[Long],
                                    fieldSelection: Option[String],
                                    fetchGeometry: Boolean,
                                    resultTransition: (Map[String, Any], List[List[Double]]) => T): Seq[T] = {
    if (linkIds.nonEmpty) vvhClient.fetchVVHRoadlinks(linkIds, fieldSelection, fetchGeometry, resultTransition)
    else Seq.empty[T]
  }

  def fetchVVHRoadlinks(municipalityCode: Int): Seq[VVHRoadlink] = {
    vvhClient.fetchByMunicipality(municipalityCode)
  }

  def getIncompleteLinks(includedMunicipalities: Option[Set[Int]]): Map[String, Map[String, Seq[Long]]] = {
    case class IncompleteLink(linkId: Long, municipality: String, administrativeClass: String)
    def toIncompleteLink(x: (Long, String, Int)) = IncompleteLink(x._1, x._2, AdministrativeClass(x._3).toString)

    withDynSession {
      val optionalMunicipalities = includedMunicipalities.map(_.mkString(","))
      val incompleteLinksQuery = """
        select l.link_id, m.name_fi, l.administrative_class
        from incomplete_link l
        join municipality m on l.municipality_code = m.id
                                 """

      val sql = optionalMunicipalities match {
        case Some(municipalities) => incompleteLinksQuery + s" where l.municipality_code in ($municipalities)"
        case _ => incompleteLinksQuery
      }

      Q.queryNA[(Long, String, Int)](sql).list
        .map(toIncompleteLink)
        .groupBy(_.municipality)
        .mapValues { _.groupBy(_.administrativeClass)
          .mapValues(_.map(_.linkId)) }
    }
  }

  def getRoadLinkMiddlePointByLinkId(linkId: Long): Option[(Long, Point)] = {
    val middlePoint: Option[Point] = vvhClient.fetchVVHRoadlink(linkId)
      .flatMap { vvhRoadLink =>
        GeometryUtils.calculatePointFromLinearReference(vvhRoadLink.geometry, GeometryUtils.geometryLength(vvhRoadLink.geometry) / 2.0)
      }
    middlePoint.map((linkId, _))
  }

  def getRoadLinkMiddlePointByMmlId(mmlId: Long): Option[(Long, Point)] = {
    vvhClient.fetchVVHRoadlinkByMmlId(mmlId).flatMap { vvhRoadLink =>
      val point = GeometryUtils.calculatePointFromLinearReference(vvhRoadLink.geometry, GeometryUtils.geometryLength(vvhRoadLink.geometry) / 2.0)
      point match {
        case Some(point) => Some(vvhRoadLink.linkId, point)
        case None => None
      }
    }
  }

  def updateProperties(linkId: Long, functionalClass: Int, linkType: LinkType,
                                direction: TrafficDirection, username: String, municipalityValidation: Int => Unit): Option[RoadLink] = {
    val vvhRoadLink = vvhClient.fetchVVHRoadlink(linkId)
    vvhRoadLink.map { vvhRoadLink =>
      municipalityValidation(vvhRoadLink.municipalityCode)
      withDynTransaction {
        setLinkProperty("traffic_direction", "traffic_direction", direction.value, linkId, username, Some(vvhRoadLink.trafficDirection.value))
        if (functionalClass != FunctionalClass.Unknown) setLinkProperty("functional_class", "functional_class", functionalClass, linkId, username)
        if (linkType != UnknownLinkType) setLinkProperty("link_type", "link_type", linkType.value, linkId, username)
        val enrichedLink = enrichRoadLinksFromVVH(Seq(vvhRoadLink)).head
        if (enrichedLink.functionalClass != FunctionalClass.Unknown && enrichedLink.linkType != UnknownLinkType) {
          removeIncompleteness(linkId)
        }
        enrichedLink
      }
    }
  }
  
  def getRoadLinkGeometry(id: Long): Option[Seq[Point]] = {
    vvhClient.fetchVVHRoadlink(id).map(_.geometry)
  }

  implicit val getAdministrativeClass = new GetResult[AdministrativeClass] {
    def apply(r: PositionedResult) = {
      AdministrativeClass(r.nextInt())
    }
  }

  implicit val getTrafficDirection = new GetResult[TrafficDirection] {
    def apply(r: PositionedResult) = {
      TrafficDirection(r.nextIntOption())
    }
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  private def updateExistingLinkPropertyRow(table: String, column: String, linkId: Long, username: String, existingValue: Int, value: Int) = {
    if (existingValue != value) {
      sqlu"""update #$table
               set #$column = $value,
                   modified_date = current_timestamp,
                   modified_by = $username
               where link_id = $linkId""".execute
    }
  }

  protected def setLinkProperty(table: String, column: String, value: Int, linkId: Long, username: String, optionalVVHValue: Option[Int] = None) = {
    val optionalExistingValue: Option[Int] = sql"""select #$column from #$table where link_id = $linkId""".as[Int].firstOption
    (optionalExistingValue, optionalVVHValue) match {
      case (Some(existingValue), _) =>
        updateExistingLinkPropertyRow(table, column, linkId, username, existingValue, value)
      case (None, None) =>
        sqlu"""insert into #$table (id, link_id, #$column, modified_by)
                 select primary_key_seq.nextval, $linkId, $value, $username
                 from dual
                 where not exists (select * from #$table where link_id = $linkId)""".execute
      case (None, Some(vvhValue)) =>
        if (vvhValue != value)
          sqlu"""insert into #$table (id, link_id, #$column, modified_by)
                   select primary_key_seq.nextval, $linkId, $value, $username
                   from dual
                   where not exists (select * from #$table where link_id = $linkId)""".execute
    }
  }

  implicit val getDateTime = new GetResult[DateTime] {
    def apply(r: PositionedResult) = {
      new DateTime(r.nextTimestamp())
    }
  }

  private def fetchTrafficDirections(idTableName: String): Seq[(Long, Int, DateTime, String)] = {
    sql"""select t.link_id, t.traffic_direction, t.modified_date, t.modified_by
            from traffic_direction t
            join #$idTableName i on i.id = t.link_id""".as[(Long, Int, DateTime, String)].list
  }

  private def fetchFunctionalClasses(idTableName: String): Seq[(Long, Int, DateTime, String)] = {
    sql"""select f.link_id, f.functional_class, f.modified_date, f.modified_by
            from functional_class f
            join #$idTableName i on i.id = f.link_id""".as[(Long, Int, DateTime, String)].list
  }

  private def fetchLinkTypes(idTableName: String): Seq[(Long, Int, DateTime, String)] = {
    sql"""select l.link_id, l.link_type, l.modified_date, l.modified_by
            from link_type l
            join #$idTableName i on i.id = l.link_id""".as[(Long, Int, DateTime, String)].list
  }

  def getRoadLinksFromVVH(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[RoadLink] = {
    // todo: store result, for now siply call vvh and print debug

    val (changes, links) = Await.result(vvhClient.fetchChangesF(bounds).zip(vvhClient.fetchVVHRoadlinksF(bounds, municipalities)), atMost = Duration.Inf)

    withDynTransaction {
      enrichRoadLinksFromVVH(links, changes)
    }
  }

  def getVVHRoadLinks(bounds: BoundingRectangle, municipalities: Set[Int] = Set()): Seq[VVHRoadlink] = {
    vvhClient.fetchVVHRoadlinks(bounds, municipalities)
  }
  def getVVHRoadLinks(bounds: BoundingRectangle): Seq[VVHRoadlink] = {
    vvhClient.fetchVVHRoadlinks(bounds)
  }

  def getRoadLinksFromVVH(linkIds: Set[Long]): Seq[RoadLink] = {
    val vvhRoadLinks = fetchVVHRoadlinks(linkIds)
    withDynTransaction {
      enrichRoadLinksFromVVH(vvhRoadLinks)
    }
  }

  def getRoadLinksFromVVH(municipality: Int): Seq[RoadLink] = {
    val (changes, links) = Await.result(vvhClient.fetchChangesF(municipality).zip(vvhClient.fetchVVHRoadlinksF(municipality)), atMost = Duration.Inf)

    withDynTransaction {
      enrichRoadLinksFromVVH(links, changes)
    }
  }

  def getRoadLinkFromVVH(linkId: Long): Option[RoadLink] = {
    val vvhRoadLinks = fetchVVHRoadlinks(Set(linkId))
    withDynTransaction {
      enrichRoadLinksFromVVH(vvhRoadLinks)
    }.headOption
  }

  def getClosestRoadlinkFromVVH(user: User, point: Point): Option[VVHRoadlink] = {
    val diagonal = Vector3d(500, 500, 0)

    val roadLinks =
      if (user.isOperator())
        getVVHRoadLinks(BoundingRectangle(point - diagonal, point + diagonal))
      else
        getVVHRoadLinks(BoundingRectangle(point - diagonal, point + diagonal), user.configuration.authorizedMunicipalities)

    if (roadLinks.isEmpty)
      None
    else
      Some(roadLinks.minBy(roadlink => minimumDistance(point, roadlink.geometry)))
  }

  protected def removeIncompleteness(linkId: Long) = {
    sqlu"""delete from incomplete_link where link_id = $linkId""".execute
  }

  def updateRoadLinkChanges(roadLinkChangeSet: RoadLinkChangeSet): Unit = {
    updateAutoGeneratedProperties(roadLinkChangeSet.adjustedRoadLinks)
    updateIncompleteLinks(roadLinkChangeSet.incompleteLinks)
  }

  def updateAutoGeneratedProperties(adjustedRoadLinks: Seq[RoadLink]) {
    def updateProperties(roadLink: RoadLink) = {
      if (roadLink.functionalClass != FunctionalClass.Unknown) setLinkProperty("functional_class", "functional_class", roadLink.functionalClass, roadLink.linkId, "automatic_generation")
      if (roadLink.linkType != UnknownLinkType) setLinkProperty("link_type", "link_type", roadLink.linkType.value, roadLink.linkId, "automatic_generation")
    }
    withDynTransaction {
      adjustedRoadLinks.foreach(updateProperties)
      adjustedRoadLinks.foreach(link =>
        if (link.functionalClass != FunctionalClass.Unknown && link.linkType != UnknownLinkType) removeIncompleteness(link.linkId)
      )
    }
  }

  protected def updateIncompleteLinks(incompleteLinks: Seq[IncompleteLink]) = {
    def setIncompleteness(incompleteLink: IncompleteLink) {
      withDynTransaction {
        sqlu"""insert into incomplete_link(id, link_id, municipality_code, administrative_class)
                 select primary_key_seq.nextval, ${incompleteLink.linkId}, ${incompleteLink.municipalityCode}, ${incompleteLink.administrativeClass.value} from dual
                 where not exists (select * from incomplete_link where link_id = ${incompleteLink.linkId})""".execute
      }
    }
    incompleteLinks.foreach(setIncompleteness)
  }

  def useValueWhenAllEqual[T](values: Seq[T]): Option[T] = {
    if (values.nonEmpty && values.forall(_ == values.head))
      Some(values.head)
    else
      None
  }
  def getLatestModification[T](values: Seq[T]): Option[T] = {
    // todo: need to find the latest value, if there is more than one
    if (values.nonEmpty)
    Some(values.head)
  else
    None
  }

  // fill incomplete links with the previous link information where they are available and where they agree
  def fillIncompleteLinksWithPreviousLinkData(incompleteLinks: Seq[RoadLink], changes: Seq[ChangeInfo]): (Seq[RoadLink], Seq[RoadLink]) = {
    val oldRoadLinkProperties = getOldRoadLinkPropertiesForChanges(changes)

    incompleteLinks.map { incompleteLink =>
      val oldIdsForIncompleteLink = changes.filter(_.newId == Option(incompleteLink.linkId)).flatMap(_.oldId)
      val oldPropertiesForIncompleteLink = oldRoadLinkProperties.filter(oldLink => oldIdsForIncompleteLink.contains(oldLink.linkId))
      val newFunctionalClass = incompleteLink.functionalClass match {
        case FunctionalClass.Unknown =>  useValueWhenAllEqual(oldPropertiesForIncompleteLink.map(_.functionalClass)).getOrElse(FunctionalClass.Unknown)
        case _ => incompleteLink.functionalClass
      }
      val newLinkType = incompleteLink.linkType match {
        case UnknownLinkType => useValueWhenAllEqual(oldPropertiesForIncompleteLink.map(_.linkType)).getOrElse(UnknownLinkType)
        case _ => incompleteLink.linkType
      }

      val newModifiedAt = getLatestModification(oldPropertiesForIncompleteLink.map(_.modifiedAt)).getOrElse(None)
      val newModifiedBy = getLatestModification(oldPropertiesForIncompleteLink.map(_.modifiedBy)).getOrElse(None)
      incompleteLink.copy(
        functionalClass  = newFunctionalClass,
        linkType          = newLinkType,
        trafficDirection  = useValueWhenAllEqual(oldPropertiesForIncompleteLink.map(_.trafficDirection)).getOrElse(incompleteLink.trafficDirection),
        modifiedAt = newModifiedAt,
        modifiedBy = newModifiedBy)
    }.partition(isComplete)
  }

  def isComplete(roadLink: RoadLink): Boolean = {
    roadLink.functionalClass != FunctionalClass.Unknown && roadLink.linkType.value != UnknownLinkType.value
  }
  def isIncomplete(roadLink: RoadLink): Boolean = !isComplete(roadLink)

  def isPartiallyIncomplete(roadLink: RoadLink): Boolean = {
    val onlyFunctionalClassIsSet = roadLink.functionalClass != FunctionalClass.Unknown && roadLink.linkType.value == UnknownLinkType.value
    val onlyLinkTypeIsSet = roadLink.functionalClass == FunctionalClass.Unknown && roadLink.linkType.value != UnknownLinkType.value
    onlyFunctionalClassIsSet || onlyLinkTypeIsSet
  }

  protected def enrichRoadLinksFromVVH(vvhRoadLinks: Seq[VVHRoadlink], changes: Seq[ChangeInfo] = Nil): Seq[RoadLink] = {
    def autoGenerateProperties(roadLink: RoadLink): RoadLink = {
      val vvhRoadLink = vvhRoadLinks.find(_.linkId == roadLink.linkId)
      vvhRoadLink.get.featureClass match {
        case FeatureClass.TractorRoad => roadLink.copy(functionalClass = 7, linkType = TractorRoad)
        case FeatureClass.DrivePath => roadLink.copy(functionalClass = 6, linkType = SingleCarriageway)
        case FeatureClass.CycleOrPedestrianPath => roadLink.copy(functionalClass = 8, linkType = CycleOrPedestrianPath)
        case _ => roadLink
      }
    }
    def toIncompleteLink(roadLink: RoadLink): IncompleteLink = {
      val vvhRoadLink = vvhRoadLinks.find(_.linkId == roadLink.linkId)
      IncompleteLink(roadLink.linkId, vvhRoadLink.get.municipalityCode, roadLink.administrativeClass)
    }

    def canBeAutoGenerated(roadLink: RoadLink): Boolean = {
      vvhRoadLinks.find(_.linkId == roadLink.linkId).get.featureClass match {
        case FeatureClass.AllOthers => false
        case _ => true
      }
    }

    val roadLinkDataByLinkId: Seq[RoadLink] = getRoadLinkDataByLinkIds(vvhRoadLinks)
    val (incompleteLinks, completeLinks) = roadLinkDataByLinkId.partition(isIncomplete)
    val (linksToAutoGenerate, incompleteOtherLinks) = incompleteLinks.partition(canBeAutoGenerated)
    val autoGeneratedLinks = linksToAutoGenerate.map(autoGenerateProperties)
    val (changedLinks, stillIncompleteLinks) = fillIncompleteLinksWithPreviousLinkData(incompleteOtherLinks, changes)
    val changedPartiallyIncompleteLinks = stillIncompleteLinks.filter(isPartiallyIncomplete)

    eventbus.publish("linkProperties:changed",
      RoadLinkChangeSet(autoGeneratedLinks ++ changedLinks ++ changedPartiallyIncompleteLinks, stillIncompleteLinks.map(toIncompleteLink)))

    completeLinks ++ autoGeneratedLinks ++ changedLinks ++ stillIncompleteLinks
  }

  def getOldRoadLinkPropertiesForChanges(changes: Seq[ChangeInfo]): Seq[RoadLinkProperties] = {
    val oldLinkIds = changes.flatMap(_.oldId)
    val propertyRows = fetchRoadLinkPropertyRows(oldLinkIds.toSet)

    oldLinkIds.map { linkId =>

      val latestModification = propertyRows.latestModifications(linkId)
      val (modifiedAt, modifiedBy) = (latestModification.map(_._1), latestModification.map(_._2))


        RoadLinkProperties(linkId,
        propertyRows.functionalClassValue(linkId),
        propertyRows.linkTypeValue(linkId),
        propertyRows.trafficDirectionValue(linkId).getOrElse(TrafficDirection.UnknownDirection),
        modifiedAt.map(DateTimePropertyFormat.print),
        modifiedBy)
    }
  }

  def getRoadLinkDataByLinkIds(vvhRoadLinks: Seq[VVHRoadlink]): Seq[RoadLink] = {
    adjustedRoadLinks(vvhRoadLinks)
  }

  private def adjustedRoadLinks(vvhRoadlinks: Seq[VVHRoadlink]): Seq[RoadLink] = {
    val propertyRows = fetchRoadLinkPropertyRows(vvhRoadlinks.map(_.linkId).toSet)

    vvhRoadlinks.map { link =>
      val latestModification = propertyRows.latestModifications(link.linkId, link.modifiedAt.map(at => (at, "vvh")))
      val (modifiedAt, modifiedBy) = (latestModification.map(_._1), latestModification.map(_._2))

      RoadLink(link.linkId, link.geometry,
        GeometryUtils.geometryLength(link.geometry),
        link.administrativeClass,
        propertyRows.functionalClassValue(link.linkId),
        propertyRows.trafficDirectionValue(link.linkId).getOrElse(link.trafficDirection),
        propertyRows.linkTypeValue(link.linkId),
        modifiedAt.map(DateTimePropertyFormat.print),
        modifiedBy, link.attributes)
    }
  }

  private def fetchRoadLinkPropertyRows(linkIds: Set[Long]): RoadLinkPropertyRows = {
    def makeMap(propertyRows: Seq[RoadLinkPropertyRow]): Map[RoadLinkId, RoadLinkPropertyRow] = {
      propertyRows.groupBy(_._1)
        .filter { case (linkId, propertyRows) => propertyRows.nonEmpty }
        .mapValues { _.head }
    }

    MassQuery.withIds(linkIds) { idTableName =>
      RoadLinkPropertyRows(
        makeMap(fetchTrafficDirections(idTableName)),
        makeMap(fetchFunctionalClasses(idTableName)),
        makeMap(fetchLinkTypes(idTableName)))
    }
  }

  type RoadLinkId = Long
  type RoadLinkPropertyRow = (Long, Int, DateTime, String)

  case class RoadLinkPropertyRows(trafficDirectionRowsByLinkId: Map[RoadLinkId, RoadLinkPropertyRow],
                                  functionalClassRowsByLinkId: Map[RoadLinkId, RoadLinkPropertyRow],
                                  linkTypeRowsByLinkId: Map[RoadLinkId, RoadLinkPropertyRow]) {

    def functionalClassValue(linkId: Long): Int = {
      val functionalClassRowOption = functionalClassRowsByLinkId.get(linkId)
      functionalClassRowOption.map(_._2).getOrElse(FunctionalClass.Unknown)
    }

    def linkTypeValue(linkId: Long): LinkType = {
      val linkTypeRowOption = linkTypeRowsByLinkId.get(linkId)
      linkTypeRowOption.map(linkTypeRow => LinkType(linkTypeRow._2)).getOrElse(UnknownLinkType)
    }

    def trafficDirectionValue(linkId: Long): Option[TrafficDirection] = {
      val trafficDirectionRowOption = trafficDirectionRowsByLinkId.get(linkId)
      trafficDirectionRowOption.map(trafficDirectionRow => TrafficDirection(trafficDirectionRow._2))
    }

    def latestModifications(linkId: Long, optionalModification: Option[(DateTime, String)] = None): Option[(DateTime, String)] = {
      val functionalClassRowOption = functionalClassRowsByLinkId.get(linkId)
      val linkTypeRowOption = linkTypeRowsByLinkId.get(linkId)
      val trafficDirectionRowOption = trafficDirectionRowsByLinkId.get(linkId)

      val modifications = List(functionalClassRowOption, trafficDirectionRowOption, linkTypeRowOption).map {
        case Some((_, _, at, by)) => Some((at, by))
        case _ => None
      } :+ optionalModification

      modifications.reduce(calculateLatestModifications)
    }

    private def calculateLatestModifications(a: Option[(DateTime, String)], b: Option[(DateTime, String)]) = {
      (a, b) match {
        case (Some((firstModifiedAt, firstModifiedBy)), Some((secondModifiedAt, secondModifiedBy))) =>
          if (firstModifiedAt.isAfter(secondModifiedAt))
            Some((firstModifiedAt, firstModifiedBy))
          else
            Some((secondModifiedAt, secondModifiedBy))
        case (Some((firstModifiedAt, firstModifiedBy)), None) => Some((firstModifiedAt, firstModifiedBy))
        case (None, Some((secondModifiedAt, secondModifiedBy))) => Some((secondModifiedAt, secondModifiedBy))
        case (None, None) => None
      }
    }
  }

  def getAdjacent(linkId: Long): Seq[RoadLink] = {
    val sourceLinkGeometryOption = getRoadLinkGeometry(linkId)
    sourceLinkGeometryOption.map(sourceLinkGeometry => {
      val sourceLinkEndpoints = GeometryUtils.geometryEndpoints(sourceLinkGeometry)
      val delta: Vector3d = Vector3d(0.1, 0.1, 0)
      val bounds = BoundingRectangle(sourceLinkEndpoints._1 - delta, sourceLinkEndpoints._1 + delta)
      val bounds2 = BoundingRectangle(sourceLinkEndpoints._2 - delta, sourceLinkEndpoints._2 + delta)
      val roadLinks = getRoadLinksFromVVH(bounds) ++ getRoadLinksFromVVH(bounds2)
      roadLinks.filterNot(_.linkId == linkId)
        .filter(roadLink => roadLink.isCarTrafficRoad)
        .filter(roadLink => {
        val targetLinkGeometry = roadLink.geometry
        GeometryUtils.areAdjacent(sourceLinkGeometry, targetLinkGeometry)
      })
    }).getOrElse(Nil)
  }
}


