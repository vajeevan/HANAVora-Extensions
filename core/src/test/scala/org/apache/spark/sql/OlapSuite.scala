package org.apache.spark.sql

import com.sap.spark.dstest.DefaultSource
import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.parser.SapParserException
import org.apache.spark.util.DummyRelationUtils._
import org.scalatest.FunSuite

class OlapSuite
  extends FunSuite
  with GlobalSapSQLContext
  with Logging {

  override def beforeAll(): Unit = {
    super.beforeAll()
    DefaultSource.reset()
  }

  def createTable(): Unit = {
    sqlContext.sql(
      """CREATE TEMPORARY TABLE t(col @(foo = 'bar') int)
        | USING com.sap.spark.dstest OPTIONS()""".stripMargin)
  }

  def createTableWithTwoColumns(): Unit = {
    sqlContext.sql(
      """CREATE TEMPORARY TABLE t(
        | col1 @(foo = 'bar') int,
        | col2 @(goo = 'moo') int)
        | USING com.sap.spark.dstest OPTIONS()""".stripMargin)
  }

  def verifyDescribe(query:String, expected: Seq[Row]): Unit = {
    val actual = sqlContext.sql(
      s"""SELECT COLUMN_NAME, ORDINAL_POSITION,
        |DATA_TYPE, ANNOTATION_KEY, ANNOTATION_VALUE
        |FROM describe_table($query)""".stripMargin).collect()
    assertResult(expected)(actual.toSeq)
  }

  test("describe table") {
    createTable()
    verifyDescribe("SELECT * FROM t", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe with new annotations a table") {
    createTable()
    verifyDescribe("SELECT col @(foo = ?) FROM t", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe table with filter") {
    createTable()
    verifyDescribe("SELECT * @(* = ?) FROM t", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe table with asterisk and a filter works") {
    createTableWithTwoColumns()
    verifyDescribe("SELECT * @(goo = ?) FROM t",
      Row("col1", 1, "INTEGER", null, null) ::
      Row("col2", 2, "INTEGER", "goo", "moo") :: Nil)
  }

  test("describe table with impossible filter produces no metadata") {
    createTable()
    verifyDescribe("SELECT * @(nonexistent = ?) FROM t",
      Row("col", 1, "INTEGER", null, null) :: Nil)
  }

  test("describe simple view") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM t")
    verifyDescribe("SELECT * FROM v", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe annotated view") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT col AS col1 @(bla = 'blo') FROM t")
    verifyDescribe("SELECT * FROM v",
      Row("col1", 1, "INTEGER", "foo", "bar") ::
        Row("col1", 1, "INTEGER", "bla", "blo") :: Nil
    )
  }

  test("describe with filters an annotated view") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT col AS col1 @(bla = 'blo') FROM t")
    verifyDescribe("SELECT col1 @(bla = ?) FROM v",
        Row("col1", 1, "INTEGER", "bla", "blo") :: Nil
    )
  }

  test("describe nested simple view") {
    createTable()
    sqlContext.sql("CREATE VIEW v1 AS SELECT * from t")
    sqlContext.sql("CREATE VIEW v2 AS SELECT col AS col1 @(foo='baz') FROM v1")
    sqlContext.sql("CREATE VIEW v3 AS SELECT col1 AS col2 @(foo='bam') FROM v2")
    verifyDescribe("SELECT col2 AS col3 @ (foo ='fap') FROM v3",
      Row("col3", 1, "INTEGER", "foo", "fap") :: Nil)
  }

  test("describe nested simple view 2") {
    createTable()
    sqlContext.sql("CREATE VIEW v1 AS SELECT * from t")
    sqlContext.sql("CREATE VIEW v2 AS SELECT col  AS col1 @(foo='bar2') FROM v1")
    sqlContext.sql("CREATE VIEW v3 AS SELECT col1 AS col2 @(foo='bar3') FROM v2")
    sqlContext.sql("CREATE VIEW v4 AS SELECT col2 AS col3 @(foo='bar4') FROM v3")
    sqlContext.sql("CREATE VIEW v5 AS SELECT col3 AS col4 @(foo='bar5') FROM v4")
    sqlContext.sql("CREATE VIEW v6 AS SELECT col4 AS col5 @(foo='bar6') FROM v5")
    verifyDescribe("SELECT col5 AS col6 @ (foo ='fap') FROM v6",
      Row("col6", 1, "INTEGER", "foo", "fap") :: Nil)
  }

  test("describe nested annotated views with annotation propagation") {
    createTable()
    sqlContext.sql("CREATE VIEW v1 AS SELECT col AS col1 @(v1key = 'v1value') FROM t")
    sqlContext.sql("CREATE VIEW v2 AS SELECT col1 AS col2 @(v2key = 'v2value') FROM v1")
    verifyDescribe("SELECT * FROM v2",
      Row("col2", 1, "INTEGER", "v1key", "v1value") ::
        Row("col2", 1, "INTEGER", "foo", "bar") ::
          Row("col2", 1, "INTEGER", "v2key", "v2value") :: Nil
    )
  }

  test("describe nested annotated views with overriding annotations") {
    createTable()
    sqlContext.sql("CREATE VIEW v1 AS SELECT col AS col1 @(foo = 'override', v1key = 'v1value') " +
      "FROM t")
    sqlContext.sql("CREATE VIEW v2 AS SELECT col1 AS col2 @(v1key = 'override') FROM v1")
    verifyDescribe("SELECT * FROM v2",
      Row("col2", 1, "INTEGER", "v1key", "override") ::
        Row("col2", 1, "INTEGER", "foo", "override") :: Nil
    )
  }

  test("describe nested annotated views with annotation propagation with filter") {
    createTable()
    sqlContext.sql("CREATE VIEW v1 AS SELECT col AS col1 @(v1key = 'v1value') FROM t")
    sqlContext.sql("CREATE VIEW v2 AS SELECT col1 AS col2 @(v2key = 'v2value') FROM v1")
    verifyDescribe("SELECT *@(v2key=?) FROM v2",
      Row("col2", 1, "INTEGER", "v2key", "v2value") :: Nil
    )
  }

  test("describe view on nested UNION works correctly") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM" +
      " (SELECT col AS col1 @(key1 = 'value1') FROM t) t1" +
      " UNION" +
      " SELECT col AS col1 @(key2 = 'value2') FROM t")
    verifyDescribe("SELECT * FROM v",
      Row("col1", 1, "INTEGER", "foo", "bar") ::
        Row("col1", 1, "INTEGER", "key1", "value1") :: Nil
    )
  }

  test("describe view on UNION works correctly") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM t UNION SELECT * FROM t WHERE col > 1")
    verifyDescribe("SELECT * FROM v", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe view on UNION ALL works correctly") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM t UNION ALL SELECT * FROM t WHERE col > 1")
    verifyDescribe("SELECT * FROM v", Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe with filter on simple view") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM t")
    verifyDescribe("SELECT * @(*=?) FROM v",
      Row("col", 1, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe with global assignment on simple view") {
    createTable()
    sqlContext.sql("CREATE VIEW v AS SELECT * FROM t")
    verifyDescribe("SELECT col @(*='override') FROM v",
      Row("col", 1, "INTEGER", "foo", "override") :: Nil)
  }

  test("describe simple join") {
    createTable()
    verifyDescribe("SELECT * FROM (SELECT col AS c1 FROM t) t1, (SELECT col AS c2 FROM t) t2",
      Row("c1", 1, "INTEGER", "foo", "bar") ::
        Row("c2", 2, "INTEGER", "foo", "bar") :: Nil
    )
  }

  test("describe annotated join") {
    createTable()
    verifyDescribe("SELECT t1.c1, t2.c2" +
      " FROM (SELECT col AS c1 @(foo = 'bar1') FROM t) t1," +
      "(SELECT col AS c2 @(bla = 'bla') FROM t) t2",
      Row("c1", 1, "INTEGER", "foo", "bar1") ::
        Row("c2", 2, "INTEGER", "foo", "bar") ::
          Row("c2", 2, "INTEGER", "bla", "bla") :: Nil)
  }

  test("describe simple join with filter") {
    createTable()
    verifyDescribe("SELECT * @(*=?) FROM (SELECT col AS c1 FROM t) t1," +
      "(SELECT col AS c2 FROM t) t2",
      Row("c1", 1, "INTEGER", "foo", "bar") ::
        Row("c2", 2, "INTEGER", "foo", "bar") :: Nil)
  }

  test("describe annotated join with filter") {
    createTable()
    verifyDescribe("SELECT * @(foo=?) FROM (SELECT col AS c1 @(foo = 'bar1') FROM t) t1," +
      "(SELECT col AS c2 @(bla = 'bla') FROM t) t2",
      Row("c1", 1, "INTEGER", "foo", "bar1") ::
        Row("c2", 2, "INTEGER", "foo", "bar") :: Nil
    )
  }

  test("describe column reference more than once") {
    createTable()
    verifyDescribe("SELECT col @ (* = 'override')," +
      "col AS col1 @ (col1 = 'col1x')," +
      "col AS col2 @ (col2 = 'col2x') FROM t",
      Row("col", 1, "INTEGER", "foo", "override") ::
      Row("col1", 2, "INTEGER", "col1", "col1x") ::
      Row("col1", 2, "INTEGER", "foo", "bar") ::
      Row("col2", 3, "INTEGER", "foo", "bar") ::
      Row("col2", 3, "INTEGER", "col2", "col2x") ::
      Nil
    )
  }

  test("describe column reference more than once with subquery") {
    createTable()
    verifyDescribe("SELECT sq.col @ (* = 'override2') FROM (" +
      "SELECT col @ (* = 'override')," +
      "col AS col1 @ (col1 = 'col1x')," +
      "col AS col2 @ (col2 = 'col2x') FROM t" +
      ") AS sq",
      Row("col", 1, "INTEGER", "foo", "override2") :: Nil
    )
  }

  test("creating a view using annotated calculated expression throws") {
    // create a table with annotation on its attribute.
    sqlContext.sql(
      """CREATE TEMPORARY TABLE T(A @(foo = 'bar') int) USING com.sap.spark.dstest OPTIONS()""")

    intercept[SapParserException] {
      sqlContext.sql(
        """CREATE VIEW V AS SELECT A+1 @(foo = 'tar') FROM T""")
    }
  }

  test("annotations defined on aliased expression works correctly (bug 106027)") {
    sqlContext.sql("""CREATE TABLE T (x int, y int) USING com.sap.spark.dstest""")

    sqlContext.sql("""CREATE VIEW V
                      |AS SELECT x + y as z@(Something='new') from T
                      |USING com.sap.spark.dstest""".stripMargin)

    verifyDescribe("SELECT * FROM V", Row("z", 1, "INTEGER", "Something", "new") :: Nil)
  }

  test("keep spark table name in case of describe without using (bug 115336)") {
    val myRelation = LogicalRelation(SqlLikeDummyRelation("t", 'foo.string)(sqlc))

    sqlContext.catalog.registerTable(TableIdentifier("t"), myRelation)
    val result = sqlContext.sql("SELECT TABLE_NAME FROM DESCRIBE_TABLE(SELECT * FROM t)").collect
    assertResult(Seq(Row("t")))(result.toSeq)
  }
}
