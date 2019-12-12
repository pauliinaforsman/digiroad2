package fi.liikennevirasto.digiroad2.dao

import fi.liikennevirasto.digiroad2.asset.{Modification, Property}
import fi.liikennevirasto.digiroad2.dao.Queries.PropertyRow
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import fi.liikennevirasto.digiroad2.asset.PropertyTypes._
import fi.liikennevirasto.digiroad2.asset.{MassTransitStopValidityPeriod, _}
import fi.liikennevirasto.digiroad2.dao.Queries._
import fi.liikennevirasto.digiroad2.service.pointasset.masstransitstop.MassTransitStopOperations
import org.joda.time.{DateTime, LocalDate}
import org.slf4j.LoggerFactory
import fi.liikennevirasto.digiroad2.dao.Queries._
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.Point
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedResult}
import scala.language.reflectiveCalls
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.{LinkGeomSource, Modification, Property}
import fi.liikennevirasto.digiroad2.dao.Queries.PropertyRow
import fi.liikennevirasto.digiroad2.service.pointasset.masstransitstop.PersistedMassTransitStop
import org.joda.time.LocalDate
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc.{GetResult, PositionedParameters, PositionedResult, SetParameter, StaticQuery => Q}
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.PropertyTypes._
import fi.liikennevirasto.digiroad2.asset.{MassTransitStopValidityPeriod, _}
import fi.liikennevirasto.digiroad2.dao.Queries._
import fi.liikennevirasto.digiroad2.model.LRMPosition
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.pointasset.masstransitstop.{LightGeometryMassTransitStop, MassTransitStopOperations, MassTransitStopRow, PersistedMassTransitStop}
import org.joda.time.{DateTime, Interval, LocalDate}
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory
import scala.language.reflectiveCalls

case class ServicePoint(id: Long, nationalId: Long, stopTypes: Seq[Int],
                        municipalityCode: Int, lon: Double, lat: Double,
                        created: Modification, modified: Modification, propertyData: Seq[Property])

case class ServicePointRow(id: Long, externalId: Long, assetTypeId: Long, point: Option[Point],
                           validFrom: Option[LocalDate], validTo: Option[LocalDate], property: PropertyRow,
                           created: Modification, modified: Modification, municipalityCode: Int, persistedFloating: Boolean)

class ServicePointBusStopDao {
  val logger = LoggerFactory.getLogger(getClass)
  def typeId: Int = MassTransitStopAsset.typeId
  val idField = "external_id" //???

  private implicit val getLocalDate = new GetResult[Option[LocalDate]] {
    def apply(r: PositionedResult) = {
      r.nextDateOption().map(new LocalDate(_))
    }
  }

  private implicit val getServicePointRow = new GetResult[ServicePointRow] {
    def apply(r: PositionedResult) = {
      val id = r.nextLong
      val externalId = r.nextLong
      val assetTypeId = r.nextLong
      val validFrom = r.nextDateOption.map(new LocalDate(_))
      val validTo = r.nextDateOption.map(new LocalDate(_))
      val point = r.nextBytesOption.map(bytesToPoint)
      val municipalityCode = r.nextInt()
      val persistedFloating = r.nextBoolean()
      val propertyId = r.nextLong
      val propertyPublicId = r.nextString
      val propertyType = r.nextString
      val propertyRequired = r.nextBoolean
      val propertyMaxCharacters = r.nextIntOption()
      val propertyValue = r.nextLongOption()
      val propertyDisplayValue = r.nextStringOption()
      val property = new PropertyRow(
        propertyId = propertyId,
        publicId = propertyPublicId,
        propertyType = propertyType,
        propertyRequired = propertyRequired,
        propertyValue = propertyValue.getOrElse(propertyDisplayValue.getOrElse("")).toString,
        propertyDisplayValue = propertyDisplayValue.orNull,
        propertyMaxCharacters = propertyMaxCharacters)
      val created = new Modification(r.nextTimestampOption().map(new DateTime(_)), r.nextStringOption)
      val modified = new Modification(r.nextTimestampOption().map(new DateTime(_)), r.nextStringOption)
      ServicePointRow(id, externalId, assetTypeId, point,
        validFrom, validTo, property, created, modified,
        municipalityCode = municipalityCode, persistedFloating = persistedFloating)
    }
  }

  def assetRowToProperty(assetRows: Iterable[ServicePointRow]): Seq[Property] = {
    assetRows.groupBy(_.property.propertyId).map { case (key, rows) =>
      val row = rows.head
      Property(
        id = key,
        publicId = row.property.publicId,
        propertyType = row.property.propertyType,
        required = row.property.propertyRequired,
        values = rows.map(assetRow =>
          PropertyValue(
            assetRow.property.propertyValue,
            propertyDisplayValueFromAssetRow(assetRow))
        ).filter(_.propertyDisplayValue.isDefined).toSeq,
        numCharacterMax = row.property.propertyMaxCharacters)
    }.toSeq
  }

  private def propertyDisplayValueFromAssetRow(assetRow: ServicePointRow): Option[String] = {
    Option(assetRow.property.propertyDisplayValue)
  }

//  private def constructValidityPeriod(validFrom: Option[LocalDate], validTo: Option[LocalDate]): String = {
//    (validFrom, validTo) match {
//      case (Some(from), None) => if (from.isAfter(LocalDate.now())) { MassTransitStopValidityPeriod.
//        Future }
//      else { MassTransitStopValidityPeriod.
//        Current }
//      case (None, Some(to)) => if (LocalDate.now().isAfter(to
//      )) { MassTransitStopValidityPeriod
//        .Past }
//      else { MassTransitStopValidityPeriod.
//        Current }
//      case (Some(from), Some(to)) =>
//        val interval = new Interval(from.toDateMidnight, to.toDateMidnight)
//        if (interval.
//          containsNow()) { MassTransitStopValidityPeriod
//          .Current }
//        else if (interval.
//          isBeforeNow) {
//          MassTransitStopValidityPeriod.Past }
//        else {
//          MassTransitStopValidityPeriod.Future }
//      case _ => MassTransitStopValidityPeriod.Current
//    }
//  }



  def fetchAsset(queryFilter: String => String): Seq[ServicePoint] = {
    val query = """
        select a.id, a.external_id, a.asset_type_id,
        a.valid_from, a.valid_to, geometry, a.municipality_code,
        p.id, p.public_id, p.property_type, p.required, p.max_value_length, e.value,
        case
          when e.name_fi is not null then e.name_fi
          when tp.value_fi is not null then tp.value_fi
          else null
        end as display_value,
        a.created_date, a.created_by, a.modified_date, a.modified_by
        from asset a
          join property p on a.asset_type_id = p.asset_type_id
          left join single_choice_value s on s.asset_id = a.id and s.property_id = p.id and p.property_type = 'single_choice'
          left join text_property_value tp on tp.asset_id = a.id and tp.property_id = p.id and (p.property_type = 'text' or p.property_type = 'long_text' or p.property_type = 'read_only_text')
          left join multiple_choice_value mc on mc.asset_id = a.id and mc.property_id = p.id and p.property_type = 'multiple_choice'
          left join enumerated_value e on (mc.enumerated_value_id = e.id and e.value = '7') or s.enumerated_value_id = e.id
      """
    queryToServicePoint(queryFilter(query))
  }

  private def queryToServicePoint(query: String): Seq[ServicePoint] = {
    val rows = Q.queryNA[ServicePointRow](query).iterator.toSeq

    rows.groupBy(_.id).map { case (id, stopRows) =>
      val row = stopRows.head
      val commonProperties: Seq[Property] = AssetPropertyConfiguration.assetRowToCommonProperties(row)
      val properties: Seq[Property] = commonProperties ++ assetRowToProperty(stopRows)
      val point = row.point.get
      val stopTypes = extractStopTypes(stopRows)

      id -> ServicePoint(id = row.id, nationalId = row.externalId, stopTypes = stopTypes,
        municipalityCode = row.municipalityCode, lon = point.x, lat = point.y, created = row.created, modified = row.modified, propertyData = properties)
    }.values.toSeq
  }

  private def extractStopTypes(rows: Seq[ServicePointRow]): Seq[Int] = {
    rows.filter(_.property.publicId.equals(MassTransitStopOperations.MassTransitStopTypePublicId)).map { row => row.property.propertyValue.toInt }
  }

  def expire(id: Long, username: String) = {
    Queries.updateAssetModified(id, username).execute
    sqlu"update asset set valid_to = sysdate where id = $id".execute
  }

  def update(assetId: Long, properties: Seq[SimpleProperty], user: String) = {
    sqlu"""
           UPDATE asset
            SET modified_by = $user, modified_date = sysdate
            WHERE id = $assetId""".execute

    updateAssetProperties(assetId, properties)
    assetId
  }

  def insertAsset(id: Long, lon: Double, lat: Double, creator: String, municipalityCode: Int): Unit = {
    val typeId = 10
    sqlu"""insert into asset (id, external_id, asset_type_id, created_by, municipality_code, geometry)
           select ($id, national_bus_stop_id_seq.nextval, $typeId, $creator, $municipalityCode,
           MDSYS.SDO_GEOMETRY(4401, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,1,1), MDSYS.SDO_ORDINATE_ARRAY($lon, $lat, 0, 0)))
           from dual
      """.execute
  }

  def updateMunicipality(id: Long, municipalityCode: Int) {
    sqlu"""
           update asset
           set municipality_code = $municipalityCode
           where id = $id
      """.execute
  }

  private[this] def createOrUpdateMultipleChoiceProperty(propertyValues: Seq[PropertyValue], assetId: Long, propertyId: Long) {
    val newValues = propertyValues.map(_.propertyValue.toLong)
    val currentIdsAndValues = Q.query[(Long, Long), (Long, Long)](multipleChoicePropertyValuesByAssetIdAndPropertyId).apply(assetId, propertyId).list
    val currentValues = currentIdsAndValues.map(_._2)
    // remove values as necessary
    currentIdsAndValues.foreach {
      case (multipleChoiceId, enumValue) =>
        if (!newValues.contains(enumValue)) {
          deleteMultipleChoiceValue(multipleChoiceId).execute
        }
    }
    // add values as necessary
    newValues.filter {
      !currentValues.contains(_)
    }.foreach {
      v =>
        insertMultipleChoiceValue(assetId, propertyId, v).execute
    }
  }

  def updateAssetLastModified(assetId: Long, modifier: String) {
    updateAssetModified(assetId, modifier).execute
  }

  private def propertyWithTypeAndId(property: SimpleProperty): Tuple3[String, Option[Long], SimpleProperty] = {
    val propertyId = Q.query[String, Long](propertyIdByPublicId).apply(property.publicId).firstOption.getOrElse(throw new IllegalArgumentException("Property: " + property.publicId + " not found"))
    (Q.query[Long, String](propertyTypeByPropertyId).apply(propertyId).first, Some(propertyId), property)
  }

  def updateAssetProperties(assetId: Long, properties: Seq[SimpleProperty]) {
    properties.map(propertyWithTypeAndId).foreach { propertyWithTypeAndId =>
      updateProperties(assetId, propertyWithTypeAndId._3.publicId, propertyWithTypeAndId._2.get, propertyWithTypeAndId._1, propertyWithTypeAndId._3.values)
    }
  }

  private def textPropertyValueDoesNotExist(assetId: Long, propertyId: Long) = {
    Q.query[(Long, Long), Long](existsTextProperty).apply((assetId, propertyId)).firstOption.isEmpty
  }

  private def singleChoiceValueDoesNotExist(assetId: Long, propertyId: Long) = {
    Q.query[(Long, Long), Long](existsSingleChoiceProperty).apply((assetId, propertyId)).firstOption.isEmpty
  }

  private def updateProperties(assetId: Long, propertyPublicId: String, propertyId: Long, propertyType: String, propertyValues: Seq[PropertyValue]) {
    propertyType match {
      case Text | LongText =>
        if (propertyValues.size > 1) throw new IllegalArgumentException("Text property must have exactly one value: " + propertyValues)
        if (propertyValues.isEmpty) {
          deleteTextProperty(assetId, propertyId).execute
        } else if (textPropertyValueDoesNotExist(assetId, propertyId)) {
          insertTextProperty(assetId, propertyId, propertyValues.head.propertyValue).execute
        } else {
          updateTextProperty(assetId, propertyId, propertyValues.head.propertyValue).execute
        }

      case MultipleChoice =>
        createOrUpdateMultipleChoiceProperty(propertyValues, assetId, propertyId)

      case SingleChoice =>
        if (propertyValues.size != 1) throw new IllegalArgumentException("Single choice property must have exactly one value. publicId: " + propertyPublicId)
        if (singleChoiceValueDoesNotExist(assetId, propertyId)) {
          insertSingleChoiceProperty(assetId, propertyId, propertyValues.head.propertyValue.toLong).execute
        } else {
          updateSingleChoiceProperty(assetId, propertyId, propertyValues.head.propertyValue.toLong).execute
        }

      case t: String => throw new UnsupportedOperationException("Asset property type: " + t + " not supported")
    }
  }
}
