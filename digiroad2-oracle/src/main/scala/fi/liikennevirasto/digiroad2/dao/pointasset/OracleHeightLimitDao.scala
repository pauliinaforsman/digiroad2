package fi.liikennevirasto.digiroad2.dao.pointasset

import fi.liikennevirasto.digiroad2.asset.LinkGeomSource
import fi.liikennevirasto.digiroad2.dao.Queries.bytesToPoint
import org.joda.time.DateTime
import slick.jdbc.{GetResult, PositionedResult, StaticQuery}

case class HeightLimit(id: Long, linkId: Long,
                               lon: Double, lat: Double,
                               mValue: Double, floating: Boolean,
                               vvhTimeStamp: Long,
                               municipalityCode: Int,
                               createdBy: Option[String] = None,
                               createdAt: Option[DateTime] = None,
                               modifiedBy: Option[String] = None,
                               modifiedAt: Option[DateTime] = None,
                               linkSource: LinkGeomSource,
                               limit: Double)
object OracleHeightLimitDao {

  def fetchByFilter(queryFilter: String => String): Seq[HeightLimit] = {
    val query =
      s"""
        select a.id, lrm.link_id, a.geometry, lrm.start_measure, a.floating, lrm.adjusted_timestamp, a.municipality_code, lrm.side_code,
        a.created_by, a.created_date, a.modified_by, a.modified_date, a.bearing, lrm.link_source, npv.value_fi,
        from asset a
        join asset_link al on a.id = al.asset_id
        join lrm_position lrm on al.position_id = lrm.id
        left join number_property_value npv on npv.asset_id = a.id
      """
    val queryWithFilter = queryFilter(query) + " and (a.valid_to > sysdate or a.valid_to is null) "
    StaticQuery.queryNA[HeightLimit](queryWithFilter).iterator.toSeq
  }

  implicit val getPointAsset = new GetResult[HeightLimit] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong()
      val linkId = r.nextLong()
      val point = r.nextBytesOption().map(bytesToPoint).get
      val mValue = r.nextDouble()
      val floating = r.nextBoolean()
      val vvhTimeStamp = r.nextLong()
      val municipalityCode = r.nextInt()
      val createdBy = r.nextStringOption()
      val createdDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val modifiedBy = r.nextStringOption()
      val modifiedDateTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val linkSource = r.nextInt()
      val limit = r.nextDouble()

      HeightLimit(id, linkId, point.x, point.y, mValue, floating, vvhTimeStamp, municipalityCode, createdBy, createdDateTime, modifiedBy, modifiedDateTime, linkSource = LinkGeomSource(linkSource), limit)
    }
  }

}
