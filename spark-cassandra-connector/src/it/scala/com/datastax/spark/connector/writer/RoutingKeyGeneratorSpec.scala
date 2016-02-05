package com.datastax.spark.connector.writer

import java.nio.ByteBuffer

import scala.concurrent.Future

import com.datastax.spark.connector.cql.{CassandraConnector, Schema}
import com.datastax.spark.connector.embedded.YamlTransformations
import com.datastax.spark.connector.{CassandraRow, SparkCassandraITFlatSpecBase}

class RoutingKeyGeneratorSpec extends SparkCassandraITFlatSpecBase {

  useCassandraConfig(Seq(YamlTransformations.Default))
  val conn = CassandraConnector(defaultConf)

  conn.withSessionDo { session =>
    createKeyspace(session)

    awaitAll(
      Future {
        session.execute( s"""CREATE TABLE $ks.one_key (id INT PRIMARY KEY, value TEXT)""")
      },
      Future {
        session.execute( s"""CREATE TABLE $ks.two_keys (id INT, id2 TEXT, value TEXT, PRIMARY KEY ((id, id2)))""")
      }
    )
  }

  val cp = conn.withClusterDo(cluster => Class.forName(cluster.getMetadata.getPartitioner).newInstance())

  "RoutingKeyGenerator" should "generate proper routing keys when there is one partition key column" in {
    val schema = Schema.fromCassandra(conn, Some(ks), Some("one_key"))
    val rowWriter = RowWriterFactory.defaultRowWriterFactory[(Int, String)].rowWriter(schema.tables.head, IndexedSeq("id", "value"))
    val rkg = new RoutingKeyGenerator(schema.tables.head, Seq("id", "value"))

    conn.withSessionDo { session =>
      val pStmt = session.prepare(s"""INSERT INTO $ks.one_key (id, value) VALUES (:id, :value)""")
      val bStmt = pStmt.bind(1: java.lang.Integer, "first row")

      session.execute(bStmt)
      val row = session.execute(s"""SELECT TOKEN(id) FROM $ks.one_key WHERE id = 1""").one()

      val readTokenStr = CassandraRow.fromJavaDriverRow(row, Array("token(id)")).getString(0)

      val rk = rkg.apply(bStmt)
      var rkTokenString = getTokenString(rk)
      rkTokenString should be(readTokenStr)
    }
  }

  it should "generate proper routing keys when there are more partition key columns" in {
    val schema = Schema.fromCassandra(conn, Some(ks), Some("two_keys"))
    val rowWriter = RowWriterFactory.defaultRowWriterFactory[(Int, String, String)].rowWriter(schema.tables.head, IndexedSeq("id", "id2", "value"))
    val rkg = new RoutingKeyGenerator(schema.tables.head, Seq("id", "id2", "value"))

    conn.withSessionDo { session =>
      val pStmt = session.prepare(s"""INSERT INTO $ks.two_keys (id, id2, value) VALUES (:id, :id2, :value)""")
      val bStmt = pStmt.bind(1: java.lang.Integer, "one", "first row")

      session.execute(bStmt)
      val row = session.execute(s"""SELECT TOKEN(id, id2) FROM $ks.two_keys WHERE id = 1 AND id2 = 'one'""").one()

      val readTokenStr = CassandraRow.fromJavaDriverRow(row, Array("token(id,id2)")).getString(0)

      val rk = rkg.apply(bStmt)
      var rkTokenString = getTokenString(rk)
      rkTokenString should be(readTokenStr)
    }
  }


  def getTokenString(routingKey: ByteBuffer): String = {
    val rkToken = cp.getClass.getMethod("getToken", classOf[ByteBuffer]).invoke(cp, routingKey)
    val rkTokenFactory = cp.getClass.getMethod("getTokenFactory").invoke(cp)
    (rkTokenFactory.getClass.getInterfaces :+ rkTokenFactory.getClass.getSuperclass)
        .find(_.getSimpleName == "TokenFactory")
        .toList
        .flatMap(_.getMethods.toList)
        .find(m => m.getName == "toString" && m.getParameterCount == 1 && m.getParameterTypes.head.getSimpleName == "Token")
        .map(_.invoke(rkTokenFactory, rkToken))
        .collectFirst { case x: String => x }
        .getOrElse("")
  }
}
