package fi.liikennevirasto.digiroad2.client.tierekisteri

import fi.liikennevirasto.digiroad2.util.Track
import org.apache.http.impl.client.CloseableHttpClient

case class TierekisteriWinterCareClassAssetData(roadNumber: Long, startRoadPartNumber: Long, endRoadPartNumber: Long,
                                                track: Track, startAddressMValue: Long, endAddressMValue: Long, assetValue: Int) extends TierekisteriAssetData

class TierekisteriWinterCareClassAssetClient(trEndPoint: String, trEnable: Boolean, httpClient: CloseableHttpClient) extends TierekisteriAssetDataClient{
  override def tierekisteriRestApiEndPoint: String = trEndPoint
  override def tierekisteriEnabled: Boolean = trEnable
  override def client: CloseableHttpClient = httpClient
  type TierekisteriType = TierekisteriWinterCareClassAssetData

  override val trAssetType = "tl132"
  private val trCareClass = "KPLK"

  override def mapFields(data: Map[String, Any]): Option[TierekisteriWinterCareClassAssetData] = {

    //Mandatory field
    val assetValue = convertToInt(getMandatoryFieldValue(data, trCareClass)).get
    val roadNumber = convertToLong(getMandatoryFieldValue(data, trRoadNumber)).get
    val roadPartNumber = convertToLong(getMandatoryFieldValue(data, trRoadPartNumber)).get
    val endRoadPartNumber = convertToLong(getMandatoryFieldValue(data, trEndRoadPartNumber)).getOrElse(roadPartNumber)
    val startMValue = convertToLong(getMandatoryFieldValue(data, trStartMValue)).get
    val endMValue = convertToLong(getMandatoryFieldValue(data, trEndMValue)).get
    val track = convertToInt(getMandatoryFieldValue(data, trTrackCode)).map(Track.apply).getOrElse(Track.Unknown)

    Some(TierekisteriWinterCareClassAssetData(roadNumber, roadPartNumber, endRoadPartNumber, track, startMValue, endMValue, assetValue))
  }
}
