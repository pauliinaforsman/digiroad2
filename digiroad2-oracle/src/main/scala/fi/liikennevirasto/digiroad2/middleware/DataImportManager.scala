package fi.liikennevirasto.digiroad2.middleware

import java.io.InputStream
import java.util.Properties

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}

sealed trait AdditionalImportValue {
  def toJson: Any
}

case class AdministrativeValues(administrativeClasses: Set[AdministrativeClass]) extends AdditionalImportValue {
  override def toJson: Any = administrativeClasses
}

case class NumericValues(values: Set[Int]) extends  AdditionalImportValue {
  override def toJson: Any = values
}

case class CsvDataImporterInfo(assetTypeName: String, fileName: String, user: User, inputStream: InputStream, additionalImportInfo: Set[AdditionalImportValue] = Set())

class DataImportManager(roadLinkService: RoadLinkService, eventBus: DigiroadEventBus) {

  lazy val trafficSignCsvImporter: TrafficSignCsvImporter = new TrafficSignCsvImporter(roadLinkService, eventBus)
  lazy val maintenanceRoadCsvImporter: MaintenanceRoadCsvImporter = new MaintenanceRoadCsvImporter(roadLinkService, eventBus)
  lazy val massTransitStopCsvImporter: MassTransitStopCsvImporter = new MassTransitStopCsvImporter(roadLinkService, eventBus)
  lazy val roadLinkCsvImporter: RoadLinkCsvImporter = new RoadLinkCsvImporter(roadLinkService, eventBus)
  lazy val obstaclesCsvImporter: ObstaclesCsvImporter = new ObstaclesCsvImporter(roadLinkService, eventBus)
  lazy val trafficLightsCsvImporter: TrafficLightsCsvImporter = new TrafficLightsCsvImporter(roadLinkService, eventBus)
  lazy val pedestrianCrossingCsvImporter: PedestrianCrossingCsvImporter = new PedestrianCrossingCsvImporter(roadLinkService, eventBus)
  lazy val railwayCrossingCsvImporter: RailwayCrossingCsvImporter = new RailwayCrossingCsvImporter(roadLinkService, eventBus)

  def importer(dataImporterInfo: CsvDataImporterInfo) {

    dataImporterInfo.assetTypeName match {
      case TrafficSigns.layerName =>
        trafficSignCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user, dataImporterInfo.additionalImportInfo.flatMap(_.asInstanceOf[NumericValues].values))
      case MaintenanceRoadAsset.layerName =>
        maintenanceRoadCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user.username)
      case "roadLinks" =>
        roadLinkCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user.username)
      case MassTransitStopAsset.layerName =>
        massTransitStopCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user, dataImporterInfo.additionalImportInfo.flatMap(_.asInstanceOf[AdministrativeValues].administrativeClasses))
      case Obstacles.layerName =>
        obstaclesCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case TrafficLights.layerName =>
        trafficLightsCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case RailwayCrossings.layerName =>
        railwayCrossingCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case DirectionalTrafficSigns.layerName =>
        ???
      case PedestrianCrossings.layerName =>
        pedestrianCrossingCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case ServicePoints.layerName =>
        ???
      case _ =>
    }
  }
}