package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql.sources.PartitioningFunctionProvider
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.DatasourceResolver._

/**
  * Base class for partitioning function related commands.
  */
private[sql] sealed trait PartitioningFunctionCommand extends RunnableCommand {

  /** The name of the affected partitioning function */
  def name: String

  /**
    * Returns a copy of this command with the given name instead of the current one.
    *
    * @param name The name value of the copy.
    * @return A copy of this command with the given name.
    */
  def withName(name: String): PartitioningFunctionCommand
}

/**
  * Base class for partitioning function creation commands.
  */
private[sql] sealed trait CreatePartitioningFunctionCommand extends PartitioningFunctionCommand

/**
 * This command creates a hash partitioning function according to the provided arguments.
 *
 * @param parameters The configuration parameters
 * @param name Name of the function to create
 * @param datatypes Datatypes of the function arguments
 * @param partitionsNo (Optional) the expected number of partitions
 * @param provider The datasource provider (has to implement [[PartitioningFunctionProvider]])
 */
private[sql] case class CreateHashPartitioningFunctionCommand(
    parameters: Map[String, String],
    name: String,
    datatypes: Seq[DataType],
    partitionsNo: Option[Int],
    provider: String)
  extends CreatePartitioningFunctionCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val resolver = resolverFor(sqlContext)
    val pfp = resolver.newInstanceOfTyped[PartitioningFunctionProvider](provider)
    pfp.createHashPartitioningFunction(sqlContext, parameters, name, datatypes, partitionsNo)
    Seq.empty
  }

  /** @inheritdoc */
  override def withName(name: String): PartitioningFunctionCommand = copy(name = name)
}

/**
  * This command creates a range-split partitioning function according to the provided arguments.
  *
  * @param parameters The configuration parameters
  * @param name Name of the function to create
  * @param datatype The function argument's datatype
  * @param splitters The range splitters
  * @param rightClosed (optional) Should be set on true if the ranges are right-closed
  * @param provider The datasource provider (has to implement [[PartitioningFunctionProvider]])
  */
private[sql] case class CreateRangeSplitPartitioningFunctionCommand(
    parameters: Map[String, String],
    name: String,
    datatype: DataType,
    splitters: Seq[Int],
    rightClosed: Boolean,
    provider: String)
  extends CreatePartitioningFunctionCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val resolver = resolverFor(sqlContext)
    val pfp = resolver.newInstanceOfTyped[PartitioningFunctionProvider](provider)
    pfp.createRangeSplitPartitioningFunction(
      sqlContext,
      parameters,
      name,
      datatype,
      splitters,
      rightClosed)
    Seq.empty
  }

  /** @inheritdoc */
  override def withName(name: String): PartitioningFunctionCommand = copy(name = name)
}

/**
  * This command creates a range-interval partitioning function according to the provided
  * definition.
  *
  * @param parameters The configuration parameters
  * @param name Name of the function to create
  * @param datatype The function argument's datatype
  * @param start The interval start
  * @param end The interval end
  * @param strideParts Either the stride value ([[Left]]) or parts value ([[Right]])
  * @param provider The datasource provider (has to implement [[PartitioningFunctionProvider]])
  */
private[sql] case class CreateRangeIntervalPartitioningFunctionCommand(
    parameters: Map[String, String],
    name: String,
    datatype: DataType,
    start: Int,
    end: Int,
    strideParts: Either[Int, Int],
    provider: String)
  extends CreatePartitioningFunctionCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val resolver = resolverFor(sqlContext)
    val pfp = resolver.newInstanceOfTyped[PartitioningFunctionProvider](provider)
    pfp.createRangeIntervalPartitioningFunction(
      sqlContext,
      parameters,
      name,
      datatype,
      start,
      end,
      strideParts)
    Seq.empty
  }

  /** @inheritdoc */
  override def withName(name: String): PartitioningFunctionCommand = copy(name = name)
}

/**
 * This command drops a partitioning function with the provided definition.
 *
 * @param parameters The configuration parameters
 * @param name The function name
 * @param allowNotExisting The flag pointing whether an exception should
 *                         be thrown when the function does not exist
 * @param provider The datasource provider (has to implement [[PartitioningFunctionProvider]])
 */
private[sql] case class DropPartitioningFunctionCommand(
    parameters: Map[String, String],
    name: String,
    allowNotExisting: Boolean,
    provider: String)
  extends PartitioningFunctionCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val resolver = resolverFor(sqlContext)
    val pfp = resolver.newInstanceOfTyped[PartitioningFunctionProvider](provider)
    pfp.dropPartitioningFunction(sqlContext, parameters, name, allowNotExisting)
    Seq.empty
  }

  /** @inheritdoc */
  override def withName(name: String): PartitioningFunctionCommand = copy(name = name)
}
