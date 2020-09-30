package fi.liikennevirasto.digiroad2.service.linearasset

import fi.liikennevirasto.digiroad2.DigiroadEventBus
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.{DynamicLinearAssetDao, MunicipalityDao, OracleAssetDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.OracleLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.{DynamicAssetValue, DynamicAssetValues, DynamicValue, LengthOfRoadAxisCreate, LengthOfRoadAxisUpdate, NewLinearAsset, PersistedLinearAsset, RoadLinkLike, Value}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.PolygonTools
import org.apache.commons.lang3.NotImplementedException
import org.joda.time.DateTime

class LengthOfRoadAxisService(roadLinkServiceImpl: RoadLinkService,
                              eventBusImpl: DigiroadEventBus)
  extends DynamicLinearAssetService(roadLinkServiceImpl: RoadLinkService,
    eventBusImpl: DigiroadEventBus) {

  override def createWithoutTransaction(typeId: Int, linkId: Long, value: Value, sideCode: Int, measures: Measures, username: String,
                                        vvhTimeStamp: Long = vvhClient.roadLinkData.createVVHTimeStamp(),
                                        roadLink: Option[RoadLinkLike], fromUpdate: Boolean = false,
                                        createdByFromUpdate: Option[String] = Some(""),
                                        createdDateTimeFromUpdate: Option[DateTime] = Some(DateTime.now()),
                                        verifiedBy: Option[String] = None, informationSource: Option[Int] = None): Long = {

    val id = dao.createLinearAsset(typeId, linkId, expired = false, sideCode, measures, username,
      vvhTimeStamp, getLinkSource(roadLink), fromUpdate, createdByFromUpdate,
      createdDateTimeFromUpdate, verifiedBy, informationSource = informationSource)
    value match {
      case DynamicValue(multiTypeProps) =>
        saveValue(typeId, id, multiTypeProps, roadLink)
      case DynamicAssetValues(manyValues) =>
        manyValues.foreach(item => {
          saveValue(typeId, id, item, roadLink)
        })
      case _ => None
    }
    id
  }

  def saveValue(typeId: Int, id: Long, multiTypeProps: DynamicAssetValue, roadLink: Option[RoadLinkLike]): Unit = {
    val properties = setPropertiesDefaultValues(multiTypeProps.properties, roadLink)
    val defaultValues = dynamicLinearAssetDao.propertyDefaultValues(typeId).filterNot(
      defaultValue => properties.exists(_.publicId == defaultValue.publicId))
    val props = properties ++ defaultValues.toSet
    validateRequiredProperties(typeId, props)
    dynamicLinearAssetDao.updateAssetProperties(id, props, typeId)
  }

  override def updateWithoutTransaction(ids: Seq[Long], value: Value, username: String, vvhTimeStamp: Option[Long] = None,
                                        sideCode: Option[Int] = None, measures: Option[Measures] = None,
                                        informationSource: Option[Int] = None): Seq[Long] = {
    if (ids.isEmpty)
      return ids

    val assetTypeId = assetDao.getAssetTypeId(ids)
    val validateValues = value.asInstanceOf[DynamicAssetValues].multipleValue
    validateValues.foreach(item => {
      validateRequiredProperties(assetTypeId.head._2, item.properties)
    })

    val assetTypeById = assetTypeId.foldLeft(Map.empty[Long, Int]) { case (m, (id, typeId)) => m + (id -> typeId) }

    ids.flatMap { id =>
      val typeId = assetTypeById(id)

      val oldLinearAsset = dynamicLinearAssetDao.fetchDynamicLinearAssetsByIds(Set(id)).head
      val newMeasures = measures.getOrElse(Measures(oldLinearAsset.startMeasure, oldLinearAsset.endMeasure))
      val newSideCode = sideCode.getOrElse(oldLinearAsset.sideCode)
      val roadLink = vvhClient.fetchRoadLinkByLinkId(oldLinearAsset.linkId).getOrElse(throw new IllegalStateException("Road link no longer available"))
      val validateItem = (validateMinDistance(newMeasures.startMeasure, oldLinearAsset.startMeasure) ||
        validateMinDistance(newMeasures.endMeasure, oldLinearAsset.endMeasure)) ||
        newSideCode != oldLinearAsset.sideCode

      value match {
        case DynamicValue(multiTypeProps) =>
          if (validateItem) {
            dao.updateExpiration(id)
            Some(createWithoutTransaction(oldLinearAsset.typeId, oldLinearAsset.linkId,
              DynamicValue(multiTypeProps), newSideCode, newMeasures, username,
              vvhClient.roadLinkData.createVVHTimeStamp(), Some(roadLink)))
          }
          else {
            Some(updateValues(id, typeId, DynamicValue(multiTypeProps), username, Some(roadLink)))
          }
        case DynamicAssetValues(manyValues) =>
          manyValues.map(item => {
            if (validateItem) {
              dao.updateExpiration(id)
              Some(createWithoutTransaction(oldLinearAsset.typeId, oldLinearAsset.linkId,
                DynamicValue(item), newSideCode, newMeasures, username,
                vvhClient.roadLinkData.createVVHTimeStamp(), Some(roadLink)))
            }
            else {
              Some(updateValues(id, typeId, DynamicValue(item), username, Some(roadLink)))
            }
          })
          Some(id)
        case _ =>
          Some(id)
      }
    }
  }

  def updateValues(id: Long, typeId: Int, value: Value, username: String, roadLink: Option[RoadLinkLike]): Long = {
    value match {
      case DynamicValue(multiTypeProps) =>
        val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, typeId)
        validateRequiredProperties(typeId, props)
        dynamicLinearAssetDao.updateAssetProperties(id, props, typeId)
        dynamicLinearAssetDao.updateAssetLastModified(id, username)
      case _ => None
    }
    id
  }

}