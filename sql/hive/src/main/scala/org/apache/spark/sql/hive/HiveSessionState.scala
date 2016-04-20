/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import java.util.regex.Pattern

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hadoop.hive.ql.parse.VariableSubstitution

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.SparkPlanner
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.hive.client.{HiveClient, HiveClientImpl}
import org.apache.spark.sql.hive.execution.{AnalyzeTable, HiveSqlParser}
import org.apache.spark.sql.internal.{SessionState, SQLConf}


/**
 * A class that holds all session-specific state in a given [[HiveContext]].
 */
private[hive] class HiveSessionState(ctx: SQLContext) extends SessionState(ctx) {

  self =>

  private lazy val sharedState: HiveSharedState = ctx.sharedState.asInstanceOf[HiveSharedState]

  /**
   * A Hive client used for execution.
   */
  lazy val executionHive: HiveClientImpl = sharedState.executionHive.newSession()

  /**
   * A Hive client used for interacting with the metastore.
   */
  lazy val metadataHive: HiveClient = sharedState.metadataHive.newSession()

  /**
   * A Hive helper class for substituting variables in a SQL statement.
   */
  lazy val substitutor = new VariableSubstitution

  override lazy val conf: SQLConf = new SQLConf {
    override def caseSensitiveAnalysis: Boolean = getConf(SQLConf.CASE_SENSITIVE, false)
  }


  /**
   * SQLConf and HiveConf contracts:
   *
   * 1. create a new o.a.h.hive.ql.session.SessionState for each HiveContext
   * 2. when the Hive session is first initialized, params in HiveConf will get picked up by the
   *    SQLConf.  Additionally, any properties set by set() or a SET command inside sql() will be
   *    set in the SQLConf *as well as* in the HiveConf.
   */
  lazy val hiveconf: HiveConf = {
    val c = executionHive.conf
    conf.setConf(c.getAllProperties)
    c
  }

  setDefaultOverrideConfs()

  /**
   * Internal catalog for managing table and database states.
   */
  override lazy val catalog = {
    new HiveSessionCatalog(
      sharedState.externalCatalog,
      metadataHive,
      ctx,
      ctx.functionResourceLoader,
      functionRegistry,
      conf,
      hiveconf)
  }

  /**
   * An analyzer that uses the Hive metastore.
   */
  override lazy val analyzer: Analyzer = {
    new Analyzer(catalog, conf) {
      override val extendedResolutionRules =
        catalog.ParquetConversions ::
        catalog.OrcConversions ::
        catalog.CreateTables ::
        catalog.PreInsertionCasts ::
        PreInsertCastAndRename ::
        DataSourceAnalysis ::
        (if (conf.runSQLOnFile) new ResolveDataSource(ctx) :: Nil else Nil)

      override val extendedCheckRules = Seq(PreWriteCheck(conf, catalog))
    }
  }

  /**
   * Parser for HiveQl query texts.
   */
  override lazy val sqlParser: ParserInterface = new HiveSqlParser(substitutor, hiveconf)

  /**
   * Planner that takes into account Hive-specific strategies.
   */
  override def planner: SparkPlanner = {
    new SparkPlanner(ctx.sparkContext, conf, experimentalMethods.extraStrategies)
      with HiveStrategies {
      override val context: SQLContext = ctx
      override val hiveconf: HiveConf = self.hiveconf

      override def strategies: Seq[Strategy] = {
        experimentalMethods.extraStrategies ++ Seq(
          FileSourceStrategy,
          DataSourceStrategy,
          HiveCommandStrategy,
          HiveDDLStrategy,
          DDLStrategy,
          SpecialLimits,
          InMemoryScans,
          HiveTableScans,
          DataSinks,
          Scripts,
          Aggregation,
          ExistenceJoin,
          EquiJoinSelection,
          BasicOperators,
          BroadcastNestedLoop,
          CartesianProduct,
          DefaultJoin
        )
      }
    }
  }


  // ------------------------------------------------------
  //  Helper methods, partially leftover from pre-2.0 days
  // ------------------------------------------------------

  override def executePlan(plan: LogicalPlan): HiveQueryExecution = {
    new HiveQueryExecution(ctx, plan)
  }

  /**
   * Overrides default Hive configurations to avoid breaking changes to Spark SQL users.
   *  - allow SQL11 keywords to be used as identifiers
   */
  def setDefaultOverrideConfs(): Unit = {
    setConf(ConfVars.HIVE_SUPPORT_SQL11_RESERVED_KEYWORDS.varname, "false")
  }

  override def setConf(key: String, value: String): Unit = {
    super.setConf(key, value)
    executionHive.runSqlHive(s"SET $key=$value")
    metadataHive.runSqlHive(s"SET $key=$value")
    hiveconf.set(key, value)
  }

  override def addJar(path: String): Unit = {
    super.addJar(path)
    executionHive.addJar(path)
    metadataHive.addJar(path)
    Thread.currentThread().setContextClassLoader(executionHive.clientLoader.classLoader)
  }

  /**
   * Analyzes the given table in the current database to generate statistics, which will be
   * used in query optimizations.
   *
   * Right now, it only supports Hive tables and it only updates the size of a Hive table
   * in the Hive metastore.
   */
  override def analyze(tableName: String): Unit = {
    AnalyzeTable(tableName).run(ctx)
  }

  /**
   * Execute a SQL statement by passing the query text directly to Hive.
   */
  override def runNativeSql(sql: String): Seq[String] = {
    val command = sql.trim.toLowerCase
    val functionOrMacroDDLPattern = Pattern.compile(
      ".*(create|drop)\\s+(temporary\\s+)?(function|macro).+", Pattern.DOTALL)
    if (functionOrMacroDDLPattern.matcher(command).matches()) {
      executionHive.runSqlHive(sql)
    } else if (command.startsWith("set")) {
      metadataHive.runSqlHive(sql)
      executionHive.runSqlHive(sql)
    } else {
      metadataHive.runSqlHive(sql)
    }
  }

  /**
   * When true, enables an experimental feature where metastore tables that use the parquet SerDe
   * are automatically converted to use the Spark SQL parquet table scan, instead of the Hive
   * SerDe.
   */
  def convertMetastoreParquet: Boolean = {
    conf.getConf(HiveContext.CONVERT_METASTORE_PARQUET)
  }

  /**
   * When true, also tries to merge possibly different but compatible Parquet schemas in different
   * Parquet data files.
   *
   * This configuration is only effective when "spark.sql.hive.convertMetastoreParquet" is true.
   */
  def convertMetastoreParquetWithSchemaMerging: Boolean = {
    conf.getConf(HiveContext.CONVERT_METASTORE_PARQUET_WITH_SCHEMA_MERGING)
  }

  /**
   * When true, enables an experimental feature where metastore tables that use the Orc SerDe
   * are automatically converted to use the Spark SQL ORC table scan, instead of the Hive
   * SerDe.
   */
  def convertMetastoreOrc: Boolean = {
    conf.getConf(HiveContext.CONVERT_METASTORE_ORC)
  }

  /**
   * When true, a table created by a Hive CTAS statement (no USING clause) will be
   * converted to a data source table, using the data source set by spark.sql.sources.default.
   * The table in CTAS statement will be converted when it meets any of the following conditions:
   *   - The CTAS does not specify any of a SerDe (ROW FORMAT SERDE), a File Format (STORED AS), or
   *     a Storage Hanlder (STORED BY), and the value of hive.default.fileformat in hive-site.xml
   *     is either TextFile or SequenceFile.
   *   - The CTAS statement specifies TextFile (STORED AS TEXTFILE) as the file format and no SerDe
   *     is specified (no ROW FORMAT SERDE clause).
   *   - The CTAS statement specifies SequenceFile (STORED AS SEQUENCEFILE) as the file format
   *     and no SerDe is specified (no ROW FORMAT SERDE clause).
   */
  def convertCTAS: Boolean = {
    conf.getConf(HiveContext.CONVERT_CTAS)
  }

  /**
   * When true, Hive Thrift server will execute SQL queries asynchronously using a thread pool."
   */
  def hiveThriftServerAsync: Boolean = {
    conf.getConf(HiveContext.HIVE_THRIFT_SERVER_ASYNC)
  }

  def hiveThriftServerSingleSession: Boolean = {
    ctx.sparkContext.conf.getBoolean(
      "spark.sql.hive.thriftServer.singleSession", defaultValue = false)
  }

}