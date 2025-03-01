package org.neo4j.spark

import java.time.{LocalTime, OffsetTime, ZoneOffset}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.SparkException
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.junit.Assert._
import org.junit.{Ignore, Test}
import org.neo4j.driver.internal.types.InternalTypeSystem
import org.neo4j.driver.internal.{InternalPoint2D, InternalPoint3D}
import org.neo4j.driver.summary.ResultSummary
import org.neo4j.driver.types.{IsoDuration, Type}
import org.neo4j.driver.{Result, Transaction, TransactionWork}
import org.neo4j.spark.util.Neo4jOptions

import scala.collection.JavaConverters._
import scala.util.Random

abstract class Neo4jType(`type`: String)

case class Duration(`type`: String = "duration",
                    months: Long,
                    days: Long,
                    seconds: Long,
                    nanoseconds: Long) extends Neo4jType(`type`)

case class Point2d(`type`: String = "point-2d",
                   srid: Int,
                   x: Double,
                   y: Double) extends Neo4jType(`type`)

case class Point3d(`type`: String = "point-3d",
                   srid: Int,
                   x: Double,
                   y: Double,
                   z: Double) extends Neo4jType(`type`)

case class Time(`type`: String = "offset-time",
                 value: String) extends Neo4jType(`type`)

case class LocalTimeValue(`type`: String = "local-time",
                     value: String) extends Neo4jType(`type`)

case class Person(name: String, surname: String, age: Int, livesIn: Point3d)

case class Person_TimeAndLocalTime(name: String, time: Time, localTime: LocalTimeValue)

case class SimplePerson(name: String, surname: String)

case class EmptyRow[T](data: T)

class DataSourceWriterTSE extends SparkConnectorScalaBaseTSE {
  val sparkSession = SparkSession.builder().getOrCreate()

  import sparkSession.implicits._

  private def testType[T](ds: DataFrame, neo4jType: Type): Unit = {
    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.foo AS foo
        |""".stripMargin).list().asScala
      .filter(r => r.get("foo").hasType(neo4jType))
      .map(r => r.asMap().asScala)
      .toSet
    val expected = ds.collect()
      .map(row => Map("foo" -> {
        val foo = row.getAs[T]("foo")
        foo match {
          case sqlDate: java.sql.Date => sqlDate
            .toLocalDate
          case sqlTimestamp: java.sql.Timestamp => sqlTimestamp.toInstant
            .atZone(ZoneOffset.UTC)
          case _ => foo
        }
      }))
      .toSet
    assertEquals(expected, records)
  }

  private def testArray[T](ds: DataFrame): Unit = {
    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.foo AS foo
        |""".stripMargin).list().asScala
      .filter(r => r.get("foo").hasType(InternalTypeSystem.TYPE_SYSTEM.LIST()))
      .map(r => r.get("foo").asList())
      .toSet
    val expected = ds.collect()
      .map(row => row.getList[T](0))
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def testThrowsExceptionIfNoValidReadOptionIsSet(): Unit = {
    try {
      ss.read.format(classOf[DataSource].getName)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .load()
        .show()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case e: IllegalArgumentException =>
        assertEquals("No valid option found. One of `query`, `labels`, `relationship` is required", e.getMessage)
      case _: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}")
    }
  }

  @Test
  def testThrowsExceptionIfTwoValidReadOptionAreSet(): Unit = {
    try {
      ss.read.format(classOf[DataSource].getName)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("labels", "Person")
        .option("relationship", "KNOWS")
        .load()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case e: IllegalArgumentException =>
        assertEquals("You need to specify just one of these options: 'labels', 'query', 'relationship'", e.getMessage)
      case _: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}")
    }
  }

  @Test
  def testThrowsExceptionIfThreeValidReadOptionAreSet(): Unit = {
    try {
      ss.read.format(classOf[DataSource].getName)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("labels", "Person")
        .option("relationship", "KNOWS")
        .option("query", "MATCH (n) RETURN n")
        .load()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case e: IllegalArgumentException =>
        assertEquals("You need to specify just one of these options: 'labels', 'query', 'relationship'", e.getMessage)
      case _: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}")
    }
  }

  @Test
  def `should write nodes with string values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .toDF("foo")

    testType[String](ds, InternalTypeSystem.TYPE_SYSTEM.STRING())
  }

  @Test
  def `should write nodes with string array values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .map(i => Array(i, i))
      .toDF("foo")

    testArray[String](ds)
  }

  @Test
  def `should write nodes with int values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i)
      .toDF("foo")

    testType[Int](ds, InternalTypeSystem.TYPE_SYSTEM.INTEGER())
  }

  @Test
  def `should write nodes with date values into Neo4j`(): Unit = {
    val total = 5
    val ds = (1 to total)
      .map(i => java.sql.Date.valueOf("2020-01-0" + i))
      .toDF("foo")

    testType[java.sql.Date](ds, InternalTypeSystem.TYPE_SYSTEM.DATE())
  }

  @Test
  def `should write nodes with timestamp values into Neo4j`(): Unit = {
    val total = 5
    val ds = (1 to total)
      .map(i => java.sql.Timestamp.valueOf(s"2020-01-0$i 11:11:11.11"))
      .toDF("foo")

    testType[java.sql.Timestamp](ds, InternalTypeSystem.TYPE_SYSTEM.DATE_TIME())
  }

  @Test
  def `should write nodes with int array values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toLong)
      .map(i => Array(i, i))
      .toDF("foo")

    testArray[Long](ds)
  }

  @Test
  def `should write nodes with point-2d values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => EmptyRow(Point2d(srid = 4326, x = Random.nextDouble(), y = Random.nextDouble())))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .filter(r => r.get("data").hasType(InternalTypeSystem.TYPE_SYSTEM.POINT()))
      .map(r => {
        val point = r.get("data").asPoint()
        (point.srid(), point.x(), point.y())
      })
      .toSet
    val expected = ds.collect()
      .map(point => (point.data.srid, point.data.x, point.data.y))
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with point-2d array values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => EmptyRow(Seq(Point2d(srid = 4326, x = Random.nextDouble(), y = Random.nextDouble()),
        Point2d(srid = 4326, x = Random.nextDouble(), y = Random.nextDouble()))))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .filter(r => r.get("data").hasType(InternalTypeSystem.TYPE_SYSTEM.LIST()))
      .map(r => r.get("data")
        .asList.asScala
        .map(_.asInstanceOf[InternalPoint2D])
        .map(point => (point.srid(), point.x(), point.y())))
      .toSet
    val expected = ds.collect()
      .map(row => row.data.map(p => (p.srid, p.x, p.y)))
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with point-3d values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => EmptyRow(Point3d(srid = 4979, x = Random.nextDouble(), y = Random.nextDouble(), z = Random.nextDouble())))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .filter(r => r.get("data").hasType(InternalTypeSystem.TYPE_SYSTEM.POINT()))
      .map(r => {
        val point = r.get("data").asPoint()
        (point.srid(), point.x(), point.y())
      })
      .toSet
    val expected = ds.collect()
      .map(point => (point.data.srid, point.data.x, point.data.y))
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with point-3d array values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => EmptyRow(Seq(Point3d(srid = 4979, x = Random.nextDouble(), y = Random.nextDouble(), z = Random.nextDouble()),
        Point3d(srid = 4979, x = Random.nextDouble(), y = Random.nextDouble(), z = Random.nextDouble()))))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .filter(r => r.get("data").hasType(InternalTypeSystem.TYPE_SYSTEM.LIST()))
      .map(r => r.get("data")
        .asList.asScala
        .map(_.asInstanceOf[InternalPoint3D])
        .map(point => (point.srid(), point.x(), point.y(), point.z())))
      .toSet
    val expected = ds.collect()
      .map(row => row.data.map(p => (p.srid, p.x, p.y, p.z)))
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with map values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => Map("field" + i -> i))
      .toDF("foo")

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":MyNode:MyLabel")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:MyNode:MyLabel)
        |RETURN p
        |""".stripMargin).list().asScala
      .filter(r => r.get("p").hasType(InternalTypeSystem.TYPE_SYSTEM.MAP()))
      .map(r => r.get("p").asMap().asScala)
      .toSet
    val expected = ds.collect().map(row => row.getMap[String, AnyRef](0))
      .map(map => map.map(t => (s"foo.${t._1}", t._2)).toMap)
      .toSet
    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with duration values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toLong)
      .map(i => EmptyRow(Duration(months = i, days = i, seconds = i, nanoseconds = i)))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "BeanWithDuration")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:BeanWithDuration)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .map(r => r.get("data").asIsoDuration())
      .map(data => (data.months, data.days, data.seconds, data.nanoseconds))
      .toSet

    val expected = ds.collect()
      .map(row => (row.data.months, row.data.days, row.data.seconds, row.data.nanoseconds))
      .toSet

    assertEquals(expected, records)
  }

  @Test
  def `should write nodes with duration array values into Neo4j`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toLong)
      .map(i => EmptyRow(Seq(Duration(months = i, days = i, seconds = i, nanoseconds = i),
        Duration(months = i, days = i, seconds = i, nanoseconds = i))))
      .toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "BeanWithDuration")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:BeanWithDuration)
        |RETURN p.data AS data
        |""".stripMargin).list().asScala
      .map(r => r.get("data")
        .asList.asScala
        .map(_.asInstanceOf[IsoDuration])
        .map(data => (data.months, data.days, data.seconds, data.nanoseconds)))
      .toSet

    val expected = ds.collect()
      .map(row => row.data.map(data => (data.months, data.days, data.seconds, data.nanoseconds)))
      .toSet

    assertEquals(expected, records)
  }
  
  @Test
  def `should write nodes into Neo4j with points`(): Unit = {
    val total = 10
    val rand = Random
    val ds = (1 to total)
      .map(i => Person(name = "Andrea " + i, "Santurbano " + i, rand.nextInt(100),
        Point3d(srid = 4979, x = 12.5811776, y = 41.9579492, z = 1.3))).toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person: Customer")
      .save()

    val count = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |WHERE p.name STARTS WITH 'Andrea'
        |AND p.surname STARTS WITH 'Santurbano'
        |RETURN count(p) AS count
        |""".stripMargin).single().get("count").asInt()
    assertEquals(total, count)

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |WHERE p.name STARTS WITH 'Andrea'
        |AND p.surname STARTS WITH 'Santurbano'
        |RETURN p.name AS name, p.surname AS surname, p.age AS age,
        | p.bornIn AS bornIn, p.livesIn AS livesIn
        |""".stripMargin).list().asScala
      .filter(r => {
        val map: java.util.Map[String, Object] = r.asMap()
        (map.get("name").isInstanceOf[String]
          && map.get("surname").isInstanceOf[String]
          && map.get("livesIn").isInstanceOf[InternalPoint3D]
          && map.get("age").isInstanceOf[Long])
      })
    assertEquals(total, records.size)
  }
  
  @Test
  def `should write nodes into Neo4j with Time and LocalTime Types`(): Unit = {
    val total = 1
    val rand = Random
    val ds = (1 to total)
      .map(i => Person_TimeAndLocalTime(name = "Andrea",time = Time(value = "12:50:35.556000000+01:00"),localTime = LocalTimeValue(value = "12:50:35.556000000"))).toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
	  .option("node.keys", "name")
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person_TimeAndLocalTime")
      .save()

    val count = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person_TimeAndLocalTime)
        |WHERE p.name STARTS WITH 'Andrea'        
        |RETURN count(p) AS count
        |""".stripMargin).single().get("count").asInt()
    assertEquals(total, count)

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person_TimeAndLocalTime)
        |WHERE p.name STARTS WITH 'Andrea'        
        |RETURN p.name AS name, p.time AS time, p.localTime AS localTime
        |""".stripMargin).list().asScala
      .filter(r => {
        val map: java.util.Map[String, Object] = r.asMap()
        (map.get("name").isInstanceOf[String]
          && map.get("time").isInstanceOf[OffsetTime]
          && map.get("localTime").isInstanceOf[LocalTime])
      })
    assertEquals(total, records.size)
  }


  @Test(expected = classOf[SparkException])
  def `should throw an error because the node already exists`(): Unit = {
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("CREATE CONSTRAINT person_surname FOR (p:Person) REQUIRE p.surname IS UNIQUE")
      })
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("CREATE (p:Person{name: 'Andrea', surname: 'Santurbano'})")
      })

    val ds = Seq(SimplePerson("Andrea", "Santurbano")).toDS()

    try {
      ds.write
        .format(classOf[DataSource].getName)
        .mode(SaveMode.Append)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("labels", "Person")
        .save()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case sparkException: SparkException => {
        val clientException = ExceptionUtils.getRootCause(sparkException)
        if (TestUtil.neo4jVersion().startsWith("4.4")) {
          assertTrue(clientException.getMessage.endsWith("share the property value ( String(\"Santurbano\") )"))
        }
        else {
          // assertTrue(clientException.getMessage.endsWith("already exists with label `Person` and property `surname` = 'Santurbano'"))
          assertTrue(clientException.getMessage.contains("New data does not satisfy Constraint")
            && clientException.getMessage.endsWith("share the property value ( String(\"Santurbano\") )"))
        }
        throw sparkException
      }
      case e: Throwable => fail(s"should be thrown a ${classOf[SparkException].getName} but is ${e.getClass.getSimpleName}")
    } finally {
      SparkConnectorScalaSuiteIT.session()
        .writeTransaction(new TransactionWork[Result] {
          override def execute(transaction: Transaction): Result = transaction.run("DROP CONSTRAINT person_surname")
        })
    }
  }

  @Test
  def `should update the node that already exists`(): Unit = {
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("CREATE CONSTRAINT person_surname FOR (p:Person) REQUIRE p.surname IS UNIQUE")
      })
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("CREATE (p:Person{name: 'Federico', surname: 'Santurbano'})")
      })

    val ds = Seq(SimplePerson("Andrea", "Santurbano")).toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "Person")
      .option("node.keys", "surname")
      .save()

    val nodeList = SparkConnectorScalaSuiteIT.session()
      .run(
        """MATCH (n:Person{surname: 'Santurbano'})
          |RETURN n
          |""".stripMargin)
      .list()
      .asScala
    assertEquals(1, nodeList.size)
    assertEquals("Andrea", nodeList.head.get("n").asNode().get("name").asString())


    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("DROP CONSTRAINT person_surname")
      })
  }

  @Test
  def `should skip null properties`(): Unit = {
    val ds = Seq(SimplePerson("Andrea", null)).toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "Person")
      .save()

    val nodeList = SparkConnectorScalaSuiteIT.session()
      .run(
        """MATCH (n:Person{name: 'Andrea'})
          |RETURN n
          |""".stripMargin)
      .list()
      .asScala
    assertEquals(1, nodeList.size)
    val node = nodeList.head.get("n").asNode()
    assertFalse("surname should not exist", node.asMap().containsKey("surname"))
  }

  @Test
  def `should throw an error because SaveMode.Overwrite need node.keys`(): Unit = {
    val ds = Seq(SimplePerson("Andrea", "Santurbano")).toDS()
    try {
      ds.write
        .format(classOf[DataSource].getName)
        .mode(SaveMode.Overwrite)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("labels", "Person")
        .save()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case illegalArgumentException: IllegalArgumentException => {
        assertTrue(illegalArgumentException.getMessage.equals(s"${Neo4jOptions.NODE_KEYS} is required when Save Mode is Overwrite"))
      }
      case e: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName} but is ${e.getClass.getSimpleName}")
    }
  }

  @Test
  def `should write within partitions`(): Unit = {
    val ds = (1 to 100).map(i => Person("Andrea " + i, "Santurbano " + i, 36, null)).toDS()
      .repartition(10)

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person:Customer")
      .option("batch.size", "11")
      .save()

    val count = SparkConnectorScalaSuiteIT.session().run(
      """
        |MATCH (p:Person:Customer)
        |WHERE p.name STARTS WITH 'Andrea'
        |AND p.surname STARTS WITH 'Santurbano'
        |RETURN count(p) AS count
        |""".stripMargin).single().get("count").asInt()
    assertEquals(100, count)

    val keys = SparkConnectorScalaSuiteIT.session().run(
      """
        |MATCH (p:Person:Customer)
        |WHERE p.name STARTS WITH 'Andrea'
        |AND p.surname STARTS WITH 'Santurbano'
        |RETURN DISTINCT keys(p) AS keys
        |""".stripMargin).single().get("keys").asList()
    assertEquals(Set("name", "surname", "age"), keys.asScala.toSet)
  }

  @Test
  @Ignore("This won't work right now because we can't know if we are in a Write or Read context")
  def `should throw an exception for a read only query`(): Unit = {
    val ds = (1 to 100).map(i => Person("Andrea " + i, "Santurbano " + i, 36, null)).toDS()

    try {
      ds.write
        .mode(SaveMode.Overwrite)
        .format(classOf[DataSource].getName)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("query", "MATCH (r:Read) RETURN r")
        .option("batch.size", "11")
        .save() // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case illegalArgumentException: IllegalArgumentException => assertTrue(illegalArgumentException.getMessage.equals("Please provide a valid WRITE query"))
      case t: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}, but it's ${t.getClass.getSimpleName}: ${t.getMessage}")
    }
  }

  @Test
  def `should insert data with a custom query`(): Unit = {
    val ds = (1 to 100).map(i => Person("Andrea " + i, "Santurbano " + i, 36, null)).toDS()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "CREATE (n:MyNode{fullName: event.name + event.surname, age: event.age - 10})")
      .option("batch.size", "11")
      .save()

    val count = SparkConnectorScalaSuiteIT.session().run(
      """
        |MATCH (p:MyNode)
        |WHERE p.fullName CONTAINS 'Andrea'
        |AND p.fullName CONTAINS 'Santurbano'
        |AND p.age = 26
        |RETURN count(p) AS count
        |""".stripMargin).single().get("count").asLong()
    assertEquals(ds.count(), count)
  }

  @Test
  def `should handle unusual column names`(): Unit = {
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("CREATE CONSTRAINT instrument_name FOR (i:Instrument) REQUIRE i.name IS UNIQUE")
      })

    val musicDf = Seq(
      (12, "John Bonham", "Drums", "f``````oo"),
      (19, "John Mayer", "Guitar", "bar"),
      (32, "John Scofield", "Guitar", "ba` z"),
      (15, "John Butler", "Guitar", "qu   ux")
    ).toDF("experience", "name", "instrument", "fi``(╯°□°)╯︵ ┻━┻eld")

    musicDf.write
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("relationship", "PLAYS")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.source.node.keys", "name")
      .option("relationship.source.node.properties", "fi``(╯°□°)╯︵ ┻━┻eld:field")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .option("relationship.target.save.mode", "Overwrite")
      .save()

    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(new TransactionWork[Result] {
        override def execute(transaction: Transaction): Result = transaction.run("DROP CONSTRAINT instrument_name")
      })

    val musicDfCheck = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.nodes.map", "false")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.target.labels", ":Instrument")
      .load()

    val size = musicDfCheck.count
    assertEquals(4, size)

    val res = musicDfCheck.orderBy("`source.name`").collectAsList()

    assertEquals("John Bonham", res.get(0).getString(4))
    assertEquals("f``````oo", res.get(0).getString(5))
    assertEquals("Drums", res.get(0).getString(8))

    assertEquals("John Butler", res.get(1).getString(4))
    assertEquals("qu   ux", res.get(1).getString(5))
    assertEquals("Guitar", res.get(1).getString(8))

    assertEquals("John Mayer", res.get(2).getString(4))
    assertEquals("bar", res.get(2).getString(5))
    assertEquals("Guitar", res.get(2).getString(8))

    assertEquals("John Scofield", res.get(3).getString(4))
    assertEquals("ba` z", res.get(3).getString(5))
    assertEquals("Guitar", res.get(3).getString(8))
  }

  @Test(expected = classOf[SparkException])
  def `should give error if native mode doesn't find a valid schema`(): Unit = {
    val musicDf = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    ).toDF("experience", "name", "instrument")

    try {
      musicDf.write
        .format(classOf[DataSource].getName)
        .mode(SaveMode.Append)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("relationship", "PLAYS")
        .option("relationship.save.strategy", "NATIVE")
        .option("relationship.source.labels", ":Person")
        .option("relationship.source.save.mode", "Overwrite")
        .option("relationship.target.labels", ":Instrument")
        .option("relationship.target.save.mode", "Overwrite")
        .save()  // we need the action to be able to trigger the exception because of the changes in Spark 3
    } catch {
      case sparkException: SparkException => {
        val clientException = ExceptionUtils.getRootCause(sparkException)
        assertTrue(clientException.getMessage.equals("NATIVE write strategy requires a schema like: rel.[props], source.[props], target.[props]. " +
          "All of this columns are empty in the current schema."))
        throw sparkException
      }
      case _: Throwable => fail(s"should be thrown a ${classOf[SparkException].getName}")
    }
  }

  @Test
  def `should write relations with KEYS mode`(): Unit = {
    val musicDf = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    ).toDF("experience", "name", "instrument")

    musicDf.repartition(1).write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.target.save.mode", "Overwrite")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name:name")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save()

    val df2 = ss.read.format(classOf[DataSource].getName)
      .option("batch.size", 100)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship.nodes.map", "false")
      .option("relationship", "PLAYS")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.target.labels", ":Instrument")
      .load()

    assertEquals(4, df2.count())

    val res = df2.orderBy("`source.name`").collectAsList()

    assertEquals("John Bonham", res.get(0).getString(4))
    assertEquals("Drums", res.get(0).getString(7))

    assertEquals("John Butler", res.get(1).getString(4))
    assertEquals("Guitar", res.get(1).getString(7))

    assertEquals("John Mayer", res.get(2).getString(4))
    assertEquals("Guitar", res.get(2).getString(7))

    assertEquals("John Scofield", res.get(3).getString(4))
    assertEquals("Guitar", res.get(3).getString(7))
  }

  @Test
  def `should fail validating options if ErrorIfExists is used`(): Unit = {
    val musicDf = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    ).toDF("experience", "name", "instrument")

    try {
      musicDf.repartition(1).write
        .format(classOf[DataSource].getName)
        .mode(SaveMode.Overwrite)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("relationship", "PLAYS")
        .option("relationship.source.save.mode", "ErrorIfExists")
        .option("relationship.target.save.mode", "Overwrite")
        .option("relationship.save.strategy", "keys")
        .option("relationship.source.labels", ":Musician")
        .option("relationship.source.node.keys", "name:name")
        .option("relationship.target.labels", ":Instrument")
        .option("relationship.target.node.keys", "instrument:name")
        .save()
    }
    catch {
      case e: IllegalArgumentException =>
        assertEquals("Save mode 'ErrorIfExists' is not supported on Spark 3.0, use 'Append' instead.", e.getMessage)
      case _: Throwable => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}")
    }
  }

  @Test
  @Ignore("trying to recreate the deadlock issue")
  def `should give better errors if transaction fails`(): Unit = {
    val df = List.fill(200)(("John Bonham", "Drums")).toDF("name", "instrument")

    df.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.target.save.mode", "Overwrite")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name:name")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save()

    df.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("transaction.retries", 0)
      .option("partitions", "10")
      .option("relationship", "PLAYS")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.target.save.mode", "Overwrite")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name:name")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save()
  }

  @Test
  def `should write relations with KEYS mode with props`(): Unit = {
    val musicDf = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    ).toDF("experience", "name", "instrument")

    musicDf.repartition(1).write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.properties", "experience")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.target.save.mode", "Overwrite")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save()

    val df2 = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship.nodes.map", "false")
      .option("relationship", "PLAYS")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.target.labels", ":Instrument")
      .load()

    assertEquals(4, df2.count())

    val res = df2.orderBy("`source.name`").collectAsList()

    assertEquals("John Bonham", res.get(0).getString(4))
    assertEquals("Drums", res.get(0).getString(7))
    assertEquals(12, res.get(0).getLong(8))

    assertEquals("John Butler", res.get(1).getString(4))
    assertEquals("Guitar", res.get(1).getString(7))
    assertEquals(15, res.get(1).getLong(8))

    assertEquals("John Mayer", res.get(2).getString(4))
    assertEquals("Guitar", res.get(2).getString(7))
    assertEquals(19, res.get(2).getLong(8))

    assertEquals("John Scofield", res.get(3).getString(4))
    assertEquals("Guitar", res.get(3).getString(7))
    assertEquals(32, res.get(3).getLong(8))
  }

  @Test
  def `should read and write relations with node overwrite mode`(): Unit = {
    val fixtureQuery: String =
      s"""CREATE (m:Musician {id: 1, name: "John Bonham"})
         |CREATE (i:Instrument {name: "Drums"})
         |CREATE (m)-[:PLAYS {experience: 10}]->(i)
         |RETURN *
    """.stripMargin

    SparkConnectorScalaSuiteIT.driver.session()
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(fixtureQuery).consume()
        })

    val musicDf = Seq(
      (1, 12, "John Henry Bonham", "Drums"),
      (2, 19, "John Mayer", "Guitar"),
      (3, 32, "John Scofield", "Guitar"),
      (4, 15, "John Butler", "Guitar")
    ).toDF("id", "experience", "name", "instrument")

    musicDf.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship.nodes.map", "false")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.target.save.mode", "Overwrite")
      .option("relationship", "PLAYS")
      .option("relationship.properties", "experience")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "id")
      .option("relationship.source.node.properties", "name")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save()

    val df2 = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship.nodes.map", "false")
      .option("relationship", "PLAYS")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.target.labels", ":Instrument")
      .load()

    val result = df2.where("`source.id` = 1")
      .collectAsList().get(0)

    assertEquals(12, result.getLong(9))
    assertEquals("John Henry Bonham", result.getString(4))
  }

  @Test
  def `should insert index while insert nodes`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .toDF("surname")

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person:Customer")
      .option("node.keys", "surname")
      .option("schema.optimization.type", "INDEX")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |RETURN p.surname AS surname
        |""".stripMargin).list().asScala
      .map(r => r.asMap().asScala)
      .toSet
    val expected = ds.collect().map(row => Map("surname" -> row.getAs[String]("surname")))
      .toSet
    assertEquals(expected, records)

    val indexCount = SparkConnectorScalaSuiteIT.session().run(
      getIndexQueryCount)
      .single()
      .get("count")
      .asLong()
    assertEquals(1, indexCount)

    SparkConnectorScalaSuiteIT.session().run("DROP INDEX spark_INDEX_Person_surname")
  }

  private def getIndexQueryCount: String = {
    val (uniqueKey, uniqueCondition) = if (TestUtil.neo4jVersion().startsWith("5.")) {
      ("owningConstraint", "owningConstraint IS NULL")
    } else {
      ("uniqueness", "uniqueness = 'NONUNIQUE'")
    }

    s"""SHOW INDEXES YIELD labelsOrTypes, properties, $uniqueKey
      |WHERE labelsOrTypes = ['Person'] AND properties = ['surname'] AND $uniqueCondition
      |RETURN count(*) AS count
      |""".stripMargin
  }

  private def getConstraintQueryCount: String = {
    val (uniqueKey, uniqueCondition) = if (TestUtil.neo4jVersion().startsWith("5.")) {
      ("owningConstraint", "owningConstraint IS NOT NULL")
    } else {
      ("uniqueness", "uniqueness = 'UNIQUE'")
    }
    s"""SHOW INDEXES YIELD labelsOrTypes, properties, $uniqueKey
      |WHERE labelsOrTypes = ['Person'] AND properties = ['surname'] AND $uniqueCondition
      |RETURN count(*) AS count
      |""".stripMargin
  }

  @Test
  def `should create constraint when insert nodes`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .toDF("surname")

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person:Customer")
      .option("node.keys", "surname")
      .option("schema.optimization.type", "NODE_CONSTRAINTS")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |RETURN p.surname AS surname
        |""".stripMargin).list().asScala
      .map(r => r.asMap().asScala)
      .toSet
    val expected = ds.collect().map(row => Map("surname" -> row.getAs[String]("surname")))
      .toSet
    assertEquals(expected, records)

    val constraintCount = SparkConnectorScalaSuiteIT.session().run(
      getConstraintQueryCount)
      .single()
      .get("count")
      .asLong()
    assertEquals(1, constraintCount)
    SparkConnectorScalaSuiteIT.session().run("DROP CONSTRAINT spark_NODE_CONSTRAINTS_Person_surname")
  }

  @Test
  def `should not create constraint when insert nodes because they already exist`(): Unit = {
    SparkConnectorScalaSuiteIT.session().run("CREATE CONSTRAINT person_surname FOR (p:Person) REQUIRE (p.surname) IS UNIQUE")
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .toDF("surname")

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person:Customer")
      .option("node.keys", "surname")
      .option("schema.optimization.type", "NODE_CONSTRAINTS")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |RETURN p.surname AS surname
        |""".stripMargin).list().asScala
      .map(r => r.asMap().asScala)
      .toSet
    val expected = ds.collect().map(row => Map("surname" -> row.getAs[String]("surname")))
      .toSet
    assertEquals(expected, records)

    val constraintCount = SparkConnectorScalaSuiteIT.session().run(
      getConstraintQueryCount)
      .single()
      .get("count")
      .asLong()
    assertEquals(1, constraintCount)
    SparkConnectorScalaSuiteIT.session().run("DROP CONSTRAINT person_surname")
  }

  @Test
  def `should insert indexes while insert with query`(): Unit = {
    val total = 10
    val ds = (1 to total)
      .map(i => i.toString)
      .toDF("surname")

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Overwrite)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", ":Person:Customer")
      .option("node.keys", "surname")
      .option("schema.optimization.type", "INDEX")
      .save()

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "CREATE (n:MyNode{fullName: event.name + event.surname, age: event.age - 10})")
      .option("batch.size", "11")
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person:Customer)
        |RETURN p.surname AS surname
        |""".stripMargin).list().asScala
      .map(r => r.asMap().asScala)
      .toSet
    val expected = ds.collect().map(row => Map("surname" -> row.getAs[String]("surname")))
      .toSet
    assertEquals(expected, records)

    val indexCount = SparkConnectorScalaSuiteIT.session()
      .run(getIndexQueryCount)
      .single()
      .get("count")
      .asLong()
    assertEquals(1, indexCount)

    SparkConnectorScalaSuiteIT.session().run("DROP INDEX spark_INDEX_Person_surname")
  }

  @Test
  def `should manage script passing the data to the executors`(): Unit = {
    val ds = Seq(SimplePerson("Andrea", "Santurbano"), SimplePerson("Davide", "Fantuzzi")).toDS()
      .repartition(2)

    ds.write
      .format(classOf[DataSource].getName)
      .mode(SaveMode.Append)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "CREATE (n:Person{fullName: event.name + ' ' + event.surname, age: scriptResult[0].age[event.name]})")
      .option("script",
        """CREATE INDEX person_surname FOR (p:Person) ON (p.surname);
          |CREATE CONSTRAINT product_name_sku FOR (p:Product)
          | REQUIRE (p.name, p.sku)
          | IS NODE KEY;
          |RETURN {Andrea: 36, Davide: 32} AS age;
          |""".stripMargin)
      .save()

    val records = SparkConnectorScalaSuiteIT.session().run(
      """MATCH (p:Person)
        |WHERE (p.fullName = 'Andrea Santurbano' AND p.age = 36)
        |OR (p.fullName = 'Davide Fantuzzi' AND p.age = 32)
        |RETURN count(p) AS count
        |""".stripMargin)
      .single()
      .get("count")
      .asLong()
    val expected = ds.count
    assertEquals(expected, records)

    val uniqueFieldName = if (TestUtil.neo4jVersion().startsWith("5.")) "owningConstraint" else "uniqueness"
    val (indexCondition, uniqueCondition) = if (TestUtil.neo4jVersion().startsWith("5.")) {
      (s"$uniqueFieldName IS NULL", s"$uniqueFieldName IS NOT NULL")
    } else {
      (s"$uniqueFieldName = 'NONUNIQUE'", s"$uniqueFieldName = 'UNIQUE'")
    }
    val query = s"""SHOW INDEXES YIELD labelsOrTypes, properties, $uniqueFieldName
        |WHERE (labelsOrTypes = ['Person'] AND properties = ['surname'] AND $indexCondition)
        |OR (labelsOrTypes = ['Product'] AND properties = ['name', 'sku'] AND $uniqueCondition)
        |RETURN count(*) AS count
        |""".stripMargin
    val constraintCount = SparkConnectorScalaSuiteIT.session()
      .run(query)
      .single()
      .get("count")
      .asLong()
    assertEquals(2, constraintCount)
    SparkConnectorScalaSuiteIT.session().run("DROP INDEX person_surname")
    SparkConnectorScalaSuiteIT.session().run("DROP CONSTRAINT product_name_sku")
  }

  @Test
  def `should work create source node and match target node`() {
    val data = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    )
    SparkConnectorScalaSuiteIT.session().run("CREATE " + data
      .map(_._3)
      .toSet[String]
      .map(instrument => s"(:Instrument{name: '$instrument'})")
      .mkString(", "))
    val musicDf = data.toDF("experience", "name", "instrument")

    musicDf.write
      .mode(SaveMode.Overwrite)
      .format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.save.mode", "Overwrite")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name")
      .option("relationship.target.save.mode", "match")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save

    val count = SparkConnectorScalaSuiteIT.session().run(
      """MATCH p = (:Musician)-[:PLAYS]->(:Instrument)
        |RETURN count(p) AS count""".stripMargin)
      .single()
      .get("count")
      .asLong()

    assertEquals(data.size, count)
  }

  @Test
  def `should work match source node and merge target node`() {
    SparkConnectorScalaSuiteIT.session().run("CREATE CONSTRAINT musician_name FOR (m:Musician) REQUIRE (m.name) IS UNIQUE")
    val data = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    )
    SparkConnectorScalaSuiteIT.session().run("CREATE " + data
      .map(_._2)
      .toSet[String]
      .map(name => s"(:Musician{name: '$name'})")
      .mkString(", "))
    val musicDf = data.toDF("experience", "name", "instrument")

    musicDf.repartition(1).write
      .mode(SaveMode.Overwrite)
      .format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.save.mode", "match")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "name")
      .option("relationship.target.save.mode", "overwrite")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save

    val count = SparkConnectorScalaSuiteIT.session().run(
      """MATCH p = (:Musician)-[:PLAYS]->(:Instrument)
        |RETURN count(p) AS count""".stripMargin)
      .single()
      .get("count")
      .asLong()

    assertEquals(data.size, count)

    SparkConnectorScalaSuiteIT.session().run("DROP CONSTRAINT musician_name")
  }

  @Test
  def `should work match source node and merge target node with odd chars`() {
    val data = Seq(
      (12, "John Bonham", "Drums"),
      (19, "John Mayer", "Guitar"),
      (32, "John Scofield", "Guitar"),
      (15, "John Butler", "Guitar")
    )
    val musicDf = data.toDF("experience", "who:name", "instrument")

    musicDf.repartition(1).write
      .mode(SaveMode.Overwrite)
      .format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "PLAYS")
      .option("relationship.save.strategy", "keys")
      .option("relationship.source.save.mode", "overwrite")
      .option("relationship.source.labels", ":Musician")
      .option("relationship.source.node.keys", "`who:name`")
      .option("relationship.target.save.mode", "overwrite")
      .option("relationship.target.labels", ":Instrument")
      .option("relationship.target.node.keys", "instrument:name")
      .save

    val count = SparkConnectorScalaSuiteIT.session().run(
      """MATCH p = (:Musician)-[:PLAYS]->(:Instrument)
        |RETURN count(p) AS count""".stripMargin)
      .single()
      .get("count")
      .asLong()

    assertEquals(data.size, count)
  }

  @Test
  def shouldWriteComplexDF(): Unit = {
    val data = Seq(
      ("Cuba Gooding Jr.", 1, "2022-06-07 00:00:00", Seq(Map("product_id" -> 1, "quantity" -> 2, "product_id" -> 2, "quantity" -> 4))),
      ("Tom Hanks", 2, "2022-07-07 00:00:00", Seq(Map("product_id" -> 11, "quantity" -> 2, "product_id" -> 22, "quantity" -> 4)))
    ).toDF("actor_name", "order_id", "order_date", "products")
    data.write
      .mode(SaveMode.Overwrite)
      .format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query",
        """
          |MERGE (person:Person {name: event.actor_name})
          |CREATE (order:Order {id: event.order_id, date: datetime(replace(event.order_date, ' ', 'T'))})
          |MERGE (person)-[:CREATED]->(order)
          |WITH event, person, order
          |UNWIND event.products AS product_order
          |MERGE (product:Product {id: product_order.product_id})
          |CREATE (order)-[:CONTAINS{quantityOrdered: product_order.quantity}]->(product)
          |""".stripMargin)
      .save()

    val actual = SparkConnectorScalaSuiteIT.session().run(
      """
        |MATCH (p:Person)-[cr:CREATED]->(o:Order)-[co:CONTAINS]->(pr:Product)
        |RETURN p.name AS name, o.id AS order, collect({id: pr.id, quantity: co.quantityOrdered}) AS products
        |""".stripMargin)
      .list()
      .asScala
      .map(_.asMap())
      .toSet
      .asJava
    val expected = Set(
      Map("name" -> "Cuba Gooding Jr.", "order" -> 1L, "products" -> List(Map("quantity" -> 4L, "id" -> 2L).asJava).asJava).asJava,
      Map("name" -> "Tom Hanks", "order" -> 2L, "products" -> List(Map("quantity" -> 4L, "id" -> 22L).asJava).asJava).asJava
    ).asJava
    assertEquals(expected, actual)
  }
}
