package org.apache.spark.sql.crossdata.catalyst.catalog.persistent.zookeeper

import java.io.IOException

import com.stratio.common.utils.components.dao.GenericDAOComponent
import com.stratio.common.utils.components.logger.impl.SparkLoggerComponent
import com.typesafe.config.Config
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{DatabaseAlreadyExistsException, NoSuchDatabaseException, NoSuchPartitionException, NoSuchPartitionsException, NoSuchTableException, PartitionAlreadyExistsException, PartitionsAlreadyExistException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils.escapePathName
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.crossdata.catalyst.catalog.persistent.XDExternalCatalog
import org.apache.spark.sql.crossdata.catalyst.catalog.persistent.XDExternalCatalog.TypesafeConfigSettings
import org.apache.spark.sql.crossdata.catalyst.catalog.persistent.models.{CatalogEntityModel, DatabaseModel, PartitionModel, TableModel}
import org.apache.spark.sql.crossdata.catalyst.catalog.persistent.zookeeper.daos.{DatabaseDAO, PartitionDAO, TableDAO}
import org.apache.hadoop.conf.Configuration

import scala.util.{Failure, Try}


class ZookeeperCatalog(settings: TypesafeConfigSettings)
  extends XDExternalCatalog[TypesafeConfigSettings](settings) {

  import settings.config

  //TODO A hadoop config is needed to deal with partitions. Should we get this config here from settings?
//  lazy val hadoopTypesafeConf: Config = settings.config.getConfig("hadoop")
  lazy val hadoopConf = new Configuration()


  // TODO we need a Catalog Config
//  protected[crossdata] lazy val config: Config = ??? //XDSharedState.catalogConfig

  /*val defaultProperties = new Properties()
  defaultProperties.setProperty("zookeeper.connectionString" , "127.0.0.1:2181")
  protected[crossdata] lazy val config: Config = ConfigFactory.parseProperties(defaultProperties)*/

  trait DaoContainer[M <: CatalogEntityModel] {
    val daoComponent: GenericDAOComponent[M] with SparkLoggerComponent
    val entityName: String
  }

  implicit lazy val databaseDAOContainer = new DaoContainer[DatabaseModel] {
    val daoComponent = new DatabaseDAO(config)
    val entityName: String = "Database"
  }

  implicit lazy val tableAndViewDAOContainer = new DaoContainer[TableModel] {
    val daoComponent = new TableDAO(config)
    val entityName: String = "Table or view"
  }

  implicit lazy val partitionDAOContainer = new DaoContainer[PartitionModel] {
    val daoComponent = new PartitionDAO(config)
    val entityName: String = "Partition"
  }

  // Erroneous dbName when db is not set in CatalogTable.identifier
  private val unknown = "unknown"

  private def getCatalogEntity[M <: CatalogEntityModel : Manifest](id: String)(
    implicit daoContainer: DaoContainer[M]
  ): Option[M] = {
    import daoContainer._
    val logFailure: PartialFunction[Throwable, Try[Option[M]]] = { case cause =>
      daoComponent.logger.warn(s"$entityName doesn't exists. Error:\n " + cause)
      Failure(cause)
    }
    daoComponent.dao.get(id).recoverWith(logFailure).toOption.flatten
  }

  private def listCatalogEntities[M <: CatalogEntityModel: Manifest](
    implicit daoContainer: DaoContainer[M]
  ): Seq[M] = daoContainer.daoComponent.dao.getAll().get

  private def getDBName(databaseModel: DatabaseModel): String = databaseModel.db.name

  private def getDBNameWithPattern(databaseModel: DatabaseModel, pattern: String): Option[String] = {
    val dbName = getDBName(databaseModel)
    if(dbName.contains(pattern)) Some(dbName) else None
  }

  private def getTableName(tableModel: TableModel): String = tableModel.tableDefinition.identifier.table

  private def getTableNameWithPattern(tableModel: TableModel, pattern: String): Option[String] = {
    val tableName = getTableName(tableModel)
    if(tableName.contains(pattern)) Some(tableName) else None
  }

  override def createDatabase(dbDefinition: CatalogDatabase, ignoreIfExists: Boolean): Unit = {
    val dao = databaseDAOContainer.daoComponent.dao
    if(!ignoreIfExists && databaseExists(dbDefinition.name))
      throw new DatabaseAlreadyExistsException(dbDefinition.name)
    dao.create(dbDefinition.name, DatabaseModel(dbDefinition))
  }

  override def dropDatabase(db: String, ignoreIfNotExists: Boolean, cascade: Boolean): Unit = {
    if(ignoreIfNotExists && !databaseExists(db))
      throw new NoSuchDatabaseException(db)
    if(cascade)
      listTables(db).foreach(table => dropTable(db, table, true, true))

    databaseDAOContainer.daoComponent.dao.delete(db)
  }

  override def alterDatabase(dbDefinition: CatalogDatabase): Unit = {
    val dbName = dbDefinition.name
    requireDbExists(dbName)
    val dao = databaseDAOContainer.daoComponent.dao
    dao.update(dbName, DatabaseModel(dbDefinition))
  }

  override def getDatabase(db: String): CatalogDatabase = {
    requireDbExists(db)
    getCatalogEntity[DatabaseModel](db).get.db
  }

  override def databaseExists(db: String): Boolean =
    getCatalogEntity[DatabaseModel](db).isDefined

  override def listDatabases(): Seq[String] = listCatalogEntities[DatabaseModel].map(getDBName)

  override def listDatabases(pattern: String): Seq[String] =
    listCatalogEntities[DatabaseModel].flatMap(db => getDBNameWithPattern(db, pattern))

  override def setCurrentDatabase(db: String): Unit = { /* no-op */ }

  override def createTable(tableDefinition: CatalogTable, ignoreIfExists: Boolean): Unit = {
    val dao = tableAndViewDAOContainer.daoComponent.dao
    val tableName = tableDefinition.identifier.table
    val dbName = tableDefinition.identifier.database.getOrElse(throw new NoSuchTableException(unknown, tableName))
    if(!ignoreIfExists && tableExists(dbName, tableName))
      throw new TableAlreadyExistsException(dbName, tableName)
    dao.create(s"$dbName.$tableName", TableModel(tableDefinition))
  }

  override def dropTable(db: String, table: String, ignoreIfNotExists: Boolean, purge: Boolean): Unit = {
    if(ignoreIfNotExists && !tableExists(db, table))
      throw new NoSuchTableException(db, table)

    tableAndViewDAOContainer.daoComponent.dao.delete(s"$db.$table")
  }

  override def renameTable(db: String, oldName: String, newName: String): Unit = {
    requireTableExists(db, oldName)
    val dao = tableAndViewDAOContainer.daoComponent.dao
    val oldTable = getTable(db, oldName)
    val newTable = oldTable.copy(identifier = TableIdentifier(newName, Some(db)))
    dao.update(s"$db.$oldName", TableModel(newTable))
  }

  override def alterTable(tableDefinition: CatalogTable): Unit = {
    val tableName = tableDefinition.identifier.table
    val dbName = tableDefinition.identifier.database.getOrElse(throw new NoSuchTableException(unknown, tableName))
    requireTableExists(dbName, tableName)
    val dao = tableAndViewDAOContainer.daoComponent.dao
    dao.update(s"$dbName.$tableName", TableModel(tableDefinition))
  }

  override def getTable(db: String, table: String): CatalogTable = {
    requireTableExists(db, table)
    getCatalogEntity[TableModel](s"$db.$table").get.tableDefinition
  }

  override def getTableOption(db: String, table: String): Option[CatalogTable] =
    getCatalogEntity[TableModel](s"$db.$table").map(t => t.tableDefinition)

  override def tableExists(db: String, table: String): Boolean = getCatalogEntity[TableModel](s"$db.$table").isDefined

  override def listTables(db: String): Seq[String] =
    listCatalogEntities[TableModel].filter { table =>
      val dbName = table.tableDefinition.identifier.database
      if(dbName.isDefined) dbName.get == db else false
    }.map(getTableName)

  override def listTables(db: String, pattern: String): Seq[String] =
    listCatalogEntities[TableModel].filter { table =>
      val dbName = table.tableDefinition.identifier.database
      if(dbName.isDefined) dbName.get == db else false
    }.flatMap(table => getTableNameWithPattern(table, pattern))

  override def loadTable(db: String, table: String, loadPath: String, isOverwrite: Boolean, holdDDLTime: Boolean): Unit =
    throw new UnsupportedOperationException("loadTable is not implemented")


  override def loadPartition(db: String, table: String, loadPath: String, partition: TablePartitionSpec, isOverwrite: Boolean, holdDDLTime: Boolean, inheritTableSpecs: Boolean): Unit =
    throw new UnsupportedOperationException("loadPartition is not implemented")

  override def loadDynamicPartitions(db: String, table: String, loadPath: String, partition: TablePartitionSpec, replace: Boolean, numDP: Int, holdDDLTime: Boolean): Unit =
    throw new UnsupportedOperationException("loadDynamicPartitions is not implemented")

  override def createPartitions(db: String, table: String, parts: Seq[CatalogTablePartition], ignoreIfExists: Boolean): Unit = {
    requireTableExists(db, table)
    val existingParts = listCatalogEntities[PartitionModel].map(_.catalogTablePartition)
    if (!ignoreIfExists) {
      val dupSpecs = parts.collect { case p if existingParts.contains(p.spec) => p.spec }
      if (dupSpecs.nonEmpty) {
        throw new PartitionsAlreadyExistException(db = db, table = table, specs = dupSpecs)
      }
    }

    val tableMeta = getTable(db, table)
    val partitionColumnNames = tableMeta.partitionColumnNames
    val tablePath = new Path(tableMeta.location)
    // TODO: we should follow hive to roll back if one partition path failed to create.
    parts.foreach { p =>
      val partitionPath = p.storage.locationUri.map(new Path(_)).getOrElse {
        ExternalCatalogUtils.generatePartitionPath(p.spec, partitionColumnNames, tablePath)
      }

      try {
        val fs = tablePath.getFileSystem(hadoopConf)
        if (!fs.exists(partitionPath)) {
          fs.mkdirs(partitionPath)
        }
      } catch {
        case e: IOException =>
          throw new SparkException(s"Unable to create partition path $partitionPath", e)
      }

      val newPartition = p.copy(storage = p.storage.copy(locationUri = Some(partitionPath.toString)))
      partitionDAOContainer.daoComponent.dao.create(s"$db.$table.${p.spec}", PartitionModel(newPartition))
    }

  }

  override def dropPartitions(db: String, table: String, parts: Seq[TablePartitionSpec], ignoreIfNotExists: Boolean, purge: Boolean, retainData: Boolean): Unit = {
    requireTableExists(db, table)

    val existingParts = listCatalogEntities[PartitionModel]
      .map(part => part.catalogTablePartition.spec -> part.catalogTablePartition)
      .toMap

    if (!ignoreIfNotExists) {
      val missingSpecs = parts.collect { case s if !existingParts.contains(s) => s }
      if (missingSpecs.nonEmpty) {
        throw new NoSuchPartitionsException(db = db, table = table, specs = missingSpecs)
      }
    }

    val shouldRemovePartitionLocation = if (retainData) {
      false
    } else {
      getTable(db, table).tableType == CatalogTableType.MANAGED
    }

    // TODO: we should follow hive to roll back if one partition path failed to delete, and support
    // partial partition spec.
    parts.foreach { p =>
      if (existingParts.contains(p) && shouldRemovePartitionLocation) {
        val partitionPath = new Path(existingParts(p).location)
        try {
          val fs = partitionPath.getFileSystem(hadoopConf)
          fs.delete(partitionPath, true)
        } catch {
          case e: IOException =>
            throw new SparkException(s"Unable to delete partition path $partitionPath", e)
        }
      }
      partitionDAOContainer.daoComponent.dao.delete(s"$db.$table.$p")

    }
  }

  private def partitionExists(db: String, table: String, spec: TablePartitionSpec): Boolean = {
    requireTableExists(db, table)
    getCatalogEntity[PartitionModel](s"$db.$table.$spec").isDefined
  }

  private def requirePartitionsExist(
                                      db: String,
                                      table: String,
                                      specs: Seq[TablePartitionSpec]): Unit = {
    specs.foreach { s =>
      if (!partitionExists(db, table, s)) {
        throw new NoSuchPartitionException(db = db, table = table, spec = s)
      }
    }
  }

  private def requirePartitionsNotExist(
                                         db: String,
                                         table: String,
                                         specs: Seq[TablePartitionSpec]): Unit = {
    specs.foreach { s =>
      if (partitionExists(db, table, s)) {
        throw new PartitionAlreadyExistsException(db = db, table = table, spec = s)
      }
    }
  }

  //TODO review existingPart in this method
  override def renamePartitions(db: String, table: String, specs: Seq[TablePartitionSpec], newSpecs: Seq[TablePartitionSpec]): Unit = {
    require(specs.size == newSpecs.size, "number of old and new partition specs differ")
    requirePartitionsExist(db, table, specs)
    requirePartitionsNotExist(db, table, newSpecs)

    val tableMeta = getTable(db, table)
    val partitionColumnNames = tableMeta.partitionColumnNames
    val tablePath = new Path(tableMeta.location)
    val shouldUpdatePartitionLocation = getTable(db, table).tableType == CatalogTableType.MANAGED
    // TODO: we should follow hive to roll back if one partition path failed to rename.
    specs.zip(newSpecs).foreach { case (oldSpec, newSpec) =>
      val oldPartition = getPartition(db, table, oldSpec)
      val newPartition = if (shouldUpdatePartitionLocation) {
        val oldPartPath = new Path(oldPartition.location)
        val newPartPath = ExternalCatalogUtils.generatePartitionPath(
          newSpec, partitionColumnNames, tablePath)
        try {
          val fs = tablePath.getFileSystem(hadoopConf)
          fs.rename(oldPartPath, newPartPath)
        } catch {
          case e: IOException =>
            throw new SparkException(s"Unable to rename partition path $oldPartPath", e)
        }
        oldPartition.copy(
          spec = newSpec,
          storage = oldPartition.storage.copy(locationUri = Some(newPartPath.toString)))
      } else {
        oldPartition.copy(spec = newSpec)
      }

      val partitionDAO = partitionDAOContainer.daoComponent.dao
      val partitionIdentifier = s"$db.$table.$oldSpec"
      partitionDAO.delete(partitionIdentifier)
      partitionDAO.update(partitionIdentifier, PartitionModel(newPartition))
    }
  }

  override def alterPartitions(db: String, table: String, parts: Seq[CatalogTablePartition]): Unit = {
    requirePartitionsExist(db, table, parts.map(p => p.spec))
    parts.foreach { partition =>
      val partitionIdentifier = s"$db.$table.${partition.spec}"
      partitionDAOContainer.daoComponent.dao.update(partitionIdentifier, PartitionModel(partition))
    }
  }

  override def getPartition(db: String, table: String, spec: TablePartitionSpec): CatalogTablePartition = {
    requireTableExists(db, table)
    getCatalogEntity[PartitionModel](s"$db.$table.$spec").map(_.catalogTablePartition)
      .getOrElse(throw new NoSuchPartitionException(db, table, spec))
  }

  override def getPartitionOption(db: String, table: String, spec: TablePartitionSpec): Option[CatalogTablePartition] =
    getCatalogEntity[PartitionModel](s"$db.$table.$spec").map(_.catalogTablePartition)

  override def listPartitionNames(db: String, table: String, partialSpec: Option[TablePartitionSpec]): Seq[String] = {

    val partitionColumnNames = getTable(db, table).partitionColumnNames
    listPartitions(db, table, partialSpec).map { partition =>
      partitionColumnNames.map { name =>
        escapePathName(name) + "=" + escapePathName(partition.spec(name))
      }.mkString("/")
    }.sorted
  }

  private def isPartialPartitionSpec(
                                      spec1: TablePartitionSpec,
                                      spec2: TablePartitionSpec): Boolean = {
    spec1.forall {
      case (partitionColumn, value) => spec2(partitionColumn) == value
    }
  }

  override def listPartitions(db: String, table: String, partialSpec: Option[TablePartitionSpec]): Seq[CatalogTablePartition] = {
    requireTableExists(db, table)

    partialSpec match {
      case None => listCatalogEntities[PartitionModel].map(_.catalogTablePartition)
      case Some(partial) =>
        listCatalogEntities[PartitionModel].map(_.catalogTablePartition).collect {
          case partition @ CatalogTablePartition(spec, _, _) if isPartialPartitionSpec(partial, spec) => partition
        }
    }
  }

  override def listPartitionsByFilter(db: String, table: String, predicates: Seq[Expression]): Seq[CatalogTablePartition] =
    throw new UnsupportedOperationException("listPartitionsByFilter is not implemented")

  override def createFunction(db: String, funcDefinition: CatalogFunction): Unit = ???

  override def dropFunction(db: String, funcName: String): Unit = ???

  override def renameFunction(db: String, oldName: String, newName: String): Unit = ???

  override def getFunction(db: String, funcName: String): CatalogFunction = ???

  override def functionExists(db: String, funcName: String): Boolean = ???

  override def listFunctions(db: String, pattern: String): Seq[String] = ???
}
