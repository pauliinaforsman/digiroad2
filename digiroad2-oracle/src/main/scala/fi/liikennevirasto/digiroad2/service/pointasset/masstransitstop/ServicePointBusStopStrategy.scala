package fi.liikennevirasto.digiroad2.service.pointasset.masstransitstop

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.dao.{AssetPropertyConfiguration, MassTransitStopDao, Sequences}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.GeometryTransform

class ServicePointBusStopStrategy(typeId : Int, massTransitStopDao: MassTransitStopDao, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus, geometryTransform: GeometryTransform) extends BusStopStrategy(typeId, massTransitStopDao, roadLinkService, eventbus, geometryTransform){

  private val validityDirectionPublicId = "vaikutussuunta"

  override def is(newProperties: Set[SimpleProperty], roadLink: Option[RoadLink], existingAssetOption: Option[PersistedMassTransitStop]): Boolean = {
    val properties = existingAssetOption match {
      case Some(existingAsset) =>
        (existingAsset.propertyData.
          filterNot(property => newProperties.exists(_.publicId == property.publicId)).
          map(property => SimpleProperty(property.publicId, property.values)) ++ newProperties).
          filterNot(property => AssetPropertyConfiguration.commonAssetProperties.exists(_._1 == property.publicId))
      case _ => newProperties.toSeq
    }

    properties.exists(p => p.publicId == MassTransitStopOperations.MassTransitStopTypePublicId &&
      p.values.exists(v => v.propertyValue == BusStopType.ServicePoint.value.toString))
  }

  override def isFloating(persistedAsset: PersistedMassTransitStop, roadLinkOption: Option[RoadLinkLike]): (Boolean, Option[FloatingReason]) = {
    (false, None)
  }

  override def publishSaveEvent(publishInfo: AbstractPublishInfo): Unit = {
    super.publishSaveEvent(publishInfo)
    eventbus.publish("service_point:saved", publishInfo)
  }

  override def enrichBusStop(asset: PersistedMassTransitStop, roadLinkOption: Option[RoadLinkLike] = None): (PersistedMassTransitStop, Boolean) = {
    (asset.copy(propertyData = asset.propertyData), false)
  }

  override def create(asset: NewMassTransitStop, username: String, point: Point, roadLink: RoadLink): (PersistedMassTransitStop, AbstractPublishInfo) = {
    val assetId = Sequences.nextPrimaryKeySeqValue
    val nationalId = massTransitStopDao.getNationalBusStopId
    val newAssetPoint = Point(asset.lon, asset.lat)
    massTransitStopDao.insertAsset(assetId, nationalId, newAssetPoint.x, newAssetPoint.y, username, roadLink.municipalityCode, floating = false)

    val defaultValues = massTransitStopDao.propertyDefaultValues(typeId).filterNot(defaultValue => asset.properties.exists(_.publicId == defaultValue.publicId))
    if (asset.properties.find(p => p.publicId == MassTransitStopOperations.MassTransitStopTypePublicId).get.values.exists(v => v.propertyValue == MassTransitStopOperations.ServicePointBusStopPropertyValue))
      throw new IllegalArgumentException

    massTransitStopDao.updateAssetProperties(assetId, asset.properties.filterNot(p =>  p.publicId == validityDirectionPublicId) ++ defaultValues.toSet)
    val resultAsset = fetchAsset(assetId)
    (resultAsset, PublishInfo(Some(resultAsset)))
  }

//  override def update(asset: PersistedMassTransitStop, optionalPosition: Option[Position], properties: Set[SimpleProperty], username: String, municipalityValidation: (Int, AdministrativeClass) => Unit, roadLink: RoadLink): (PersistedMassTransitStop, AbstractPublishInfo) = {
//
//    if (asset.propertyData.find(p => p.publicId == MassTransitStopOperations.MassTransitStopTypePublicId).get.values.exists(v => v.propertyValue == MassTransitStopOperations.ServicePointBusStopPropertyValue))
//      throw new IllegalArgumentException
//
//     //Enrich properties with old administrator, if administrator value is empty in CSV import
//    val verifiedProperties = MassTransitStopOperations.getVerifiedProperties(properties, asset.propertyData)
//
//    val id = asset.id
//    massTransitStopDao.updateAssetLastModified(id, username)
//    massTransitStopDao.updateAssetProperties(id, verifiedProperties.filterNot(p =>  ignoredProperties.contains(p.publicId)).toSeq)
//    updateAdministrativeClassValue(id, roadLink.administrativeClass)
//    val oldChildren = massTransitStopDao.getAllChildren(id)
//
//    optionalPosition.map(updatePosition(id, roadLink))
//
//    if(properties.exists(p => p.publicId == terminalChildrenPublicId)){
//      val children = MassTransitStopOperations.getTerminalMassTransitStopChildren(properties.toSeq)
//      massTransitStopDao.deleteChildren(id)
//      massTransitStopDao.insertChildren(id, children)
//
//      val resultAsset = enrichBusStop(fetchAsset(id))._1
//      (resultAsset, TerminalPublishInfo(Some(resultAsset), children.diff(oldChildren), oldChildren.diff(children)))
//
//    } else {
//      val resultAsset = enrichBusStop(fetchAsset(id))._1
//      (resultAsset, TerminalPublishInfo(Some(resultAsset), Seq(), Seq()))
//    }
//  }
//
//  override def delete(asset: PersistedMassTransitStop): Option[AbstractPublishInfo] = {
//    val oldChildren = massTransitStopDao.getAllChildren(asset.id)
//    massTransitStopDao.deleteTerminalMassTransitStopData(asset.id)
//
//    Some(TerminalPublishInfo(None, Seq(), oldChildren))
//  }
}
