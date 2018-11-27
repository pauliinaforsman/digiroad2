package fi.liikennevirasto.digiroad2.process

import fi.liikennevirasto.digiroad2.asset.{AssetTypeInfo, PropertyValue, TrailerTruckWeightLimit, TrafficSignType}
import fi.liikennevirasto.digiroad2.dao.pointasset.PersistedTrafficSign
import fi.liikennevirasto.digiroad2.linearasset.PersistedLinearAsset

class TrailerTruckWeightLimitValidator extends SevenRestrictionsLimitationValidator{
  override def assetTypeInfo: AssetTypeInfo = TrailerTruckWeightLimit
  override val allowedTrafficSign: Set[TrafficSignType] = Set(TrafficSignType.MaxMassCombineVehiclesExceeding)

  override def comparingAssetAndTrafficValue(asset: PersistedLinearAsset, trafficSign: PersistedTrafficSign): Boolean = {
    TrafficSignType.apply(trafficSignService.getTrafficSignsProperties(trafficSign, "trafficSigns_type").get.propertyValue.toInt) match {
      case TrafficSignType.MaxMassCombineVehiclesExceeding =>
        trafficSignService.getTrafficSignsProperties(trafficSign, "trafficSigns_value").getOrElse(PropertyValue("")).propertyValue == getAssetValue(asset)
      case _ => throw new NumberFormatException(s"Not supported trafficSign on ${assetTypeInfo.label} asset")
    }
  }
}
