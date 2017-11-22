package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.PointAssetFiller.AssetAdjustment
import fi.liikennevirasto.digiroad2.asset.SideCode._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.RoadLinkLike
import fi.liikennevirasto.digiroad2.pointasset.oracle.{OracleTrafficSignDao, PersistedTrafficSign}
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}

import scala.util.Try

case class IncomingTrafficSign(lon: Double, lat: Double, linkId: Long, propertyData: Set[SimpleProperty], validityDirection: Int, bearing: Option[Int]) extends IncomingPointAsset

sealed trait TrafficSignTypeGroup {
  def value: Int
}
object TrafficSignTypeGroup {
  val values = Set(Unknown, SpeedLimits, PedestrianCrossing, MaximumRestrictions, GeneralWarningSigns, TurningRestrictions)

  def apply(intValue: Int): TrafficSignTypeGroup = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object SpeedLimits extends TrafficSignTypeGroup { def value = 1  }
  case object PedestrianCrossing extends TrafficSignTypeGroup { def value = 2 }
  case object MaximumRestrictions extends TrafficSignTypeGroup { def value = 3 }
  case object GeneralWarningSigns extends TrafficSignTypeGroup { def value = 4 }
  case object TurningRestrictions extends TrafficSignTypeGroup { def value = 5 }
  case object Unknown extends TrafficSignTypeGroup { def value = 99 }
}

sealed trait TrafficSignType {
  def value: Int
  def group: TrafficSignTypeGroup
}
object TrafficSignType {
  val values = Set(Unknown, SpeedLimit, EndSpeedLimit, SpeedLimitZone, EndSpeedLimitZone, UrbanArea, EndUrbanArea, PedestrianCrossing, MaximumLength, Warning, NoLeftTurn, NoRightTurn, NoUTurn)

  def apply(intValue: Int): TrafficSignType = {
    values.find(_.value == intValue).getOrElse(Unknown)
  }

  case object SpeedLimit extends TrafficSignType { def value = 1;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndSpeedLimit extends TrafficSignType { def value = 2;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object SpeedLimitZone extends TrafficSignType { def value = 3;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndSpeedLimitZone extends TrafficSignType { def value = 4;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object UrbanArea extends TrafficSignType { def value = 5;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object EndUrbanArea extends TrafficSignType { def value = 6;  def group = TrafficSignTypeGroup.SpeedLimits; }
  case object PedestrianCrossing extends TrafficSignType { def value = 7;  def group = TrafficSignTypeGroup.PedestrianCrossing; }
  case object MaximumLength extends TrafficSignType { def value = 8;  def group = TrafficSignTypeGroup.MaximumRestrictions; }
  case object Warning extends TrafficSignType { def value = 9;  def group = TrafficSignTypeGroup.GeneralWarningSigns; }
  case object NoLeftTurn extends TrafficSignType { def value = 10;  def group = TrafficSignTypeGroup.TurningRestrictions; }
  case object NoRightTurn extends TrafficSignType { def value = 11;  def group = TrafficSignTypeGroup.TurningRestrictions; }
  case object NoUTurn extends TrafficSignType { def value = 12;  def group = TrafficSignTypeGroup.TurningRestrictions; }
  case object Unknown extends TrafficSignType { def value = 99;  def group = TrafficSignTypeGroup.Unknown; }
}

class TrafficSignService(val roadLinkService: RoadLinkService, val userProvider: UserProvider) extends PointAssetOperations {
  type IncomingAsset = IncomingTrafficSign
  type PersistedAsset = PersistedTrafficSign

  override def typeId: Int = 300

  private def setAssetPosition(asset: IncomingAsset, geometry: Seq[Point], mValue: Double): IncomingAsset = {
    GeometryUtils.calculatePointFromLinearReference(geometry, mValue) match {
      case Some(point) =>
        asset.copy(lon = point.x, lat = point.y)
      case _ =>
        asset
    }
  }

  private val typePublicId = "trafficSigns_type"
  private val valuePublicId = "trafficSigns_value"
  private val infoPublicId = "trafficSigns_info"

  private val additionalInfoTypeGroups = Set(TrafficSignTypeGroup.GeneralWarningSigns, TrafficSignTypeGroup.TurningRestrictions)

  override def fetchPointAssets(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[PersistedTrafficSign] = OracleTrafficSignDao.fetchByFilter(queryFilter)

  override def setFloating(persistedAsset: PersistedTrafficSign, floating: Boolean): PersistedTrafficSign = {
    persistedAsset.copy(floating = floating)
  }

  override def create(asset: IncomingTrafficSign, username: String, geometry: Seq[Point], municipality: Int, administrativeClass: Option[AdministrativeClass] = None, linkSource: LinkGeomSource): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat, 0), geometry)
    withDynTransaction {
      OracleTrafficSignDao.create(setAssetPosition(asset, geometry, mValue), mValue, username, municipality, VVHClient.createVVHTimeStamp(), linkSource)
    }
  }

  override def update(id: Long, updatedAsset: IncomingTrafficSign, geometry: Seq[Point], municipality: Int, username: String, linkSource: LinkGeomSource): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat, 0), geometry)
    withDynTransaction {
      OracleTrafficSignDao.update(id, setAssetPosition(updatedAsset, geometry, mValue), mValue, municipality, username, Some(VVHClient.createVVHTimeStamp()), linkSource)
    }
    id
  }

  override def getByBoundingBox(user: User, bounds: BoundingRectangle): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(bounds)
    super.getByBoundingBox(user, bounds, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createOperation(asset: PersistedAsset, adjustment: AssetAdjustment): PersistedAsset = {
    createPersistedAsset(asset, adjustment)
  }

  private def adjustmentOperation(persistedAsset: PersistedAsset, adjustment: AssetAdjustment): Long = {
    val updated = IncomingTrafficSign(adjustment.lon, adjustment.lat, adjustment.linkId,
      persistedAsset.propertyData.map(prop => SimpleProperty(prop.publicId, prop.values)).toSet,
      persistedAsset.validityDirection, persistedAsset.bearing)

    OracleTrafficSignDao.update(adjustment.assetId, updated, adjustment.mValue, persistedAsset.municipalityCode,
      "vvh_generated", Some(adjustment.vvhTimeStamp), persistedAsset.linkSource)
  }

  override def getByMunicipality(municipalityCode: Int): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    getByMunicipality(municipalityCode, mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
  }

  private def createPersistedAsset[T](persistedStop: PersistedAsset, asset: AssetAdjustment) = {
    new PersistedAsset(asset.assetId, asset.linkId, asset.lon, asset.lat,
      asset.mValue, asset.floating, persistedStop.vvhTimeStamp, persistedStop.municipalityCode, persistedStop.propertyData, persistedStop.createdBy,
      persistedStop.createdAt, persistedStop.modifiedBy, persistedStop.modifiedAt, persistedStop.validityDirection, persistedStop.bearing,
      persistedStop.linkSource)
  }

  def getAssetValidityDirection(bearing: Int): Option[Int] = {
    if(bearing >= 270 || bearing < 90){
      Some(AgainstDigitizing.value)
    }else{
      Some(TowardsDigitizing.value)
    }
  }

  def getTrafficSignValidityDirection(assetLocation: Point, geometry: Seq[Point]): Int = {

    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(assetLocation.x, assetLocation.y, 0), geometry)
    val roadLinkPoint = GeometryUtils.calculatePointFromLinearReference(geometry, mValue)
    val linkBearing = GeometryUtils.calculateBearing(geometry)

    val lonDifference = assetLocation.x - roadLinkPoint.get.x
    val latDifference = assetLocation.y - roadLinkPoint.get.y


    if((latDifference <= 0 && linkBearing <= 90) || (latDifference >= 0 && linkBearing > 270)) {
      TowardsDigitizing.value
    }else{
      AgainstDigitizing.value
    }
  }

  def getAssetBearing(validityDirection: Int, geometry: Seq[Point] ): Int = {
    val linkBearing = GeometryUtils.calculateBearing(geometry)
    GeometryUtils.calculateActualBearing(validityDirection, Some(linkBearing)).get
  }

  private def generateProperties(tRTrafficSignType: TRTrafficSignType, value: Any) = {
    val trafficType = tRTrafficSignType.trafficSignType
    val typeProperty = SimpleProperty(typePublicId, Seq(PropertyValue(trafficType.value.toString)))
    val valueProperty = additionalInfoTypeGroups.exists(group => group == trafficType.group) match {
      case true => SimpleProperty(infoPublicId, Seq(PropertyValue(value.toString)))
      case _ => SimpleProperty(valuePublicId, Seq(PropertyValue(value.toString)))
    }

    Set(typeProperty, valueProperty)
  }

  def tryToInt(propertyValue: String ) = {
    Try(propertyValue.toInt).toOption
  }

  def createFromCoordinates(lon: Long, lat: Long, trafficSignType: TRTrafficSignType, value: Option[Int], twoSided: Option[Boolean], trafficDirection: TrafficDirection, bearing: Option[Int]) ={
    roadLinkService.getClosestRoadlinkForCarTrafficFromVVH(userProvider.getCurrentUser(), Point(lon, lat)).map { vvhroad =>
      roadLinkService.getRoadLinkFromVVH(vvhroad.linkId).filter(_.functionalClass != CycleOrPedestrianPath.value) match {
        case Some(link) =>
          bearing match {
            case Some(assetBearing) =>
              val validityDirection = if(twoSided.get){
                BothDirections.value
              }else{
                getAssetValidityDirection(assetBearing).get
              }
              val asset = IncomingTrafficSign(lon, lat, link.linkId, generateProperties(trafficSignType, value.getOrElse("")), validityDirection, Some(GeometryUtils.calculateBearing(link.geometry)))
              create(asset, userProvider.getCurrentUser().username, link.geometry, link.municipalityCode, Some(link.administrativeClass), link.linkSource)
            case None =>
              val validityDirection = if(twoSided.get){
                BothDirections.value
              }else{
                getTrafficSignValidityDirection(Point(lon, lat), link.geometry)
              }
              val asset = IncomingTrafficSign(lon, lat, link.linkId, generateProperties(trafficSignType, value.getOrElse("")), validityDirection , Some(GeometryUtils.calculateBearing(link.geometry)))
              create(asset, userProvider.getCurrentUser().username, link.geometry, link.municipalityCode, Some(link.administrativeClass), link.linkSource)
          }
        case _ => None
      }
    }
  }
}
