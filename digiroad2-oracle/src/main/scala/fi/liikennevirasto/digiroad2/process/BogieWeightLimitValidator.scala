package fi.liikennevirasto.digiroad2.process

import fi.liikennevirasto.digiroad2.asset.{AssetTypeInfo, BogieWeightLimit, PropertyValue, TextPropertyValue}
import fi.liikennevirasto.digiroad2.dao.pointasset.PersistedTrafficSign
import fi.liikennevirasto.digiroad2.linearasset.PersistedLinearAsset
import fi.liikennevirasto.digiroad2.service.pointasset.TrafficSignType

class BogieWeightLimitValidator extends SevenRestrictionsLimitationValidator {
  override def assetTypeInfo: AssetTypeInfo = BogieWeightLimit
  override val allowedTrafficSign: Set[TrafficSignType] = Set(TrafficSignType.MaxTonsOnBogieExceeding)

  override def comparingAssetAndTrafficValue(asset: PersistedLinearAsset, trafficSign: PersistedTrafficSign): Boolean = {
    TrafficSignType.apply(trafficSignService.getTrafficSignsProperties(trafficSign, "trafficSigns_type").get.asInstanceOf[TextPropertyValue].propertyValue.toInt) match {
      case TrafficSignType.MaxTonsOnBogieExceeding =>
        trafficSignService.getTrafficSignsProperties(trafficSign, "trafficSigns_value").getOrElse(PropertyValue("")).asInstanceOf[TextPropertyValue].propertyValue == getAssetValue(asset)
      case _ => throw new NumberFormatException(s"Not supported trafficSign on ${assetTypeInfo.label} asset")
    }
  }
}